package dev.raggateway.rag.ingest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link OutboxStore} on the {@code outbox} table (V4, C4) with {@link JdbcClient}.
 *
 * <p>The claim picks the rows in a {@code MATERIALIZED} CTE, so the locking sub-select runs exactly once (D-47
 * amendment): written as {@code WHERE id IN (SELECT … LIMIT n FOR UPDATE SKIP LOCKED)}, a plan that re-scans the
 * sub-select per outer row skips the rows the statement has just updated and returns new ones each time, so one
 * claim could take every claimable row. Otherwise it is the LLD statement with the batch size and lease as parameters (D-47: 4 jobs, 5 minutes): one
 * {@code UPDATE} whose subquery picks claimable rows oldest first with {@code FOR UPDATE SKIP LOCKED}. A row
 * another claimer has locked is skipped rather than waited for, and a row another claimer has already committed as
 * IN_PROGRESS no longer matches the predicate when it is re-checked under the lock, so two claimers never get the
 * same job. A crashed worker's jobs come back when their lease runs out. Outside a Spring transaction the statement
 * is its own short transaction (autocommit), as the contract asks; the lock lasts only that statement.
 *
 * <p>t11 (D-51): a released job stays PENDING with {@code lease_until} as its retry time, and the claim skips it
 * until then. {@code markDone}, {@code release} and {@code markDead} are fenced by the attempt number the claim
 * returned ({@code WHERE id = ? AND attempts = ? AND status = 'IN_PROGRESS'}), so a worker whose lease ran out and
 * whose job was claimed again changes nothing. The claim predicate still only reads PENDING and IN_PROGRESS rows,
 * so it keeps using the partial index {@code outbox_claimable}.
 *
 * <p>{@code now()} is the database clock, so all instances share one clock for leases and retry times.
 */
@Repository
public class JdbcOutboxStore implements OutboxStore {

    /** {@code last_error} is for an operator reading the table; a stack trace or a huge message is cut. */
    static final int MAX_ERROR_CHARS = 2_000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcOutboxStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tx = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public UUID insert(UUID documentId, String contentHash) {
        return jdbc.sql("INSERT INTO outbox (document_id, content_hash) VALUES (:doc, :hash) RETURNING id")
                .param("doc", documentId)
                .param("hash", contentHash)
                .query(UUID.class)
                .single();
    }

    @Override
    public List<OutboxEvent> claimBatch(int max, Duration lease) {
        if (max <= 0) throw new IllegalArgumentException("max must be positive");
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        record Claimed(OutboxEvent event, Instant createdAt) {
        }
        List<Claimed> claimed = jdbc.sql("""
                        WITH picked AS MATERIALIZED (
                          SELECT id FROM outbox
                          WHERE (status = 'PENDING' AND (lease_until IS NULL OR lease_until <= now()))
                             OR (status = 'IN_PROGRESS' AND lease_until < now())
                          ORDER BY created_at
                          LIMIT :max
                          FOR UPDATE SKIP LOCKED
                        )
                        UPDATE outbox o SET status = 'IN_PROGRESS',
                                            lease_until = now() + :leaseMs * interval '1 millisecond',
                                            attempts = o.attempts + 1
                          FROM picked
                         WHERE o.id = picked.id
                        RETURNING o.id, o.document_id, o.content_hash, o.attempts, o.created_at
                        """)
                .param("leaseMs", lease.toMillis())
                .param("max", max)
                .query((rs, n) -> new Claimed(
                        new OutboxEvent(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                                rs.getString("content_hash"), rs.getInt("attempts")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        // RETURNING has no order; hand the jobs back oldest first, as they were picked.
        return claimed.stream().sorted(Comparator.comparing(Claimed::createdAt)).map(Claimed::event).toList();
    }

    @Override
    public boolean markDone(UUID id, int attempt) {
        return jdbc.sql("""
                        UPDATE outbox SET status = 'DONE', lease_until = NULL
                        WHERE id = :id AND attempts = :attempt AND status = 'IN_PROGRESS'
                        """)
                .param("id", id)
                .param("attempt", attempt)
                .update() == 1;
    }

    @Override
    public boolean release(UUID id, int attempt, String error, Duration retryAfter) {
        if (retryAfter == null || retryAfter.isNegative()) throw new IllegalArgumentException("retryAfter must be >= 0");
        return jdbc.sql("""
                        UPDATE outbox SET status = 'PENDING',
                                          lease_until = now() + :retryMs * interval '1 millisecond',
                                          last_error = :error
                        WHERE id = :id AND attempts = :attempt AND status = 'IN_PROGRESS'
                        """)
                .param("id", id)
                .param("attempt", attempt)
                .param("retryMs", retryAfter.toMillis())
                .param("error", truncate(error))
                .update() == 1;
    }

    @Override
    public DeadLetter markDead(UUID id, int attempt, String error) {
        DeadLetter result = tx.execute(status -> {
            // Document row first, then the job row: the lock order of the PUT, the swap and the delete's cascade.
            jdbc.sql("""
                            SELECT d.id FROM documents d JOIN outbox o ON o.document_id = d.id
                            WHERE o.id = :id FOR UPDATE OF d
                            """)
                    .param("id", id)
                    .query(UUID.class)
                    .optional();
            List<Map<String, Object>> dead = jdbc.sql("""
                            UPDATE outbox SET status = 'DEAD', lease_until = NULL, last_error = :error
                            WHERE id = :id AND attempts = :attempt AND status = 'IN_PROGRESS'
                            RETURNING document_id, content_hash
                            """)
                    .param("id", id)
                    .param("attempt", attempt)
                    .param("error", truncate(error))
                    .query()
                    .listOfRows();
            if (dead.isEmpty()) return new DeadLetter(false, false);
            int failed = jdbc.sql("""
                            UPDATE documents SET status = 'failed', updated_at = now()
                            WHERE id = :doc AND content_hash = :hash AND status = 'queued'
                            """)
                    .param("doc", dead.getFirst().get("document_id"))
                    .param("hash", dead.getFirst().get("content_hash"))
                    .update();
            return new DeadLetter(true, failed == 1);
        });
        return result == null ? new DeadLetter(false, false) : result;
    }

    @Override
    public long countUnresolvedDead() {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox o
                        WHERE o.status = 'DEAD'
                          AND NOT EXISTS (SELECT 1 FROM outbox n
                                          WHERE n.document_id = o.document_id AND n.created_at > o.created_at)
                        """)
                .query(Long.class)
                .single();
    }

    static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS);
    }
}
