package dev.raggateway.rag.ingest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link OutboxStore} on the {@code outbox} table (V4, C4) with {@link JdbcClient}.
 *
 * <p>The claim is the LLD statement with the batch size and lease as parameters (D-47: 4 jobs, 5 minutes): one
 * {@code UPDATE} whose subquery picks claimable rows oldest first with {@code FOR UPDATE SKIP LOCKED}. A row
 * another claimer has locked is skipped rather than waited for, and a row another claimer has already committed as
 * IN_PROGRESS no longer matches the predicate when it is re-checked under the lock, so two claimers never get the
 * same job. A crashed worker's jobs come back when their lease runs out. Outside a Spring transaction the statement
 * is its own short transaction (autocommit), as the contract asks; the lock lasts only that statement.
 *
 * <p>{@code now()} is the database clock, so all instances share one clock for leases.
 */
@Repository
public class JdbcOutboxStore implements OutboxStore {

    /** {@code last_error} is for an operator reading the table; a stack trace or a huge message is cut. */
    static final int MAX_ERROR_CHARS = 2_000;

    private final JdbcClient jdbc;

    public JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
                        UPDATE outbox SET status = 'IN_PROGRESS',
                                          lease_until = now() + :leaseMs * interval '1 millisecond',
                                          attempts = attempts + 1
                        WHERE id IN (
                          SELECT id FROM outbox
                          WHERE status = 'PENDING' OR (status = 'IN_PROGRESS' AND lease_until < now())
                          ORDER BY created_at
                          LIMIT :max
                          FOR UPDATE SKIP LOCKED
                        )
                        RETURNING id, document_id, content_hash, attempts, created_at
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
    public void markDone(UUID id) {
        jdbc.sql("UPDATE outbox SET status = 'DONE', lease_until = NULL WHERE id = :id").param("id", id).update();
    }

    @Override
    public void release(UUID id, String error) {
        jdbc.sql("UPDATE outbox SET status = 'PENDING', lease_until = NULL, last_error = :error WHERE id = :id")
                .param("id", id)
                .param("error", truncate(error))
                .update();
    }

    @Override
    public void markDead(UUID id, String error) {
        jdbc.sql("UPDATE outbox SET status = 'DEAD', lease_until = NULL, last_error = :error WHERE id = :id")
                .param("id", id)
                .param("error", truncate(error))
                .update();
    }

    static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS);
    }
}
