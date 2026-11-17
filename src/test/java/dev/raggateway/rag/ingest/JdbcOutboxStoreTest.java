package dev.raggateway.rag.ingest;

import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox claim against real Postgres: {@code FOR UPDATE SKIP LOCKED} with a lease (LLD claim SQL, D-47).
 *
 * <p>The table is shared with other test classes, so a claim may also pick up their leftover jobs. Assertions are
 * about this test's own jobs (its own tenant), plus "no id twice" over everything claimed.
 */
class JdbcOutboxStoreTest extends AbstractPostgresTest {

    static final Duration LEASE = Duration.ofMinutes(5);

    final JdbcOutboxStore outbox = new JdbcOutboxStore(jdbc, new DataSourceTransactionManager(dataSource));
    final JdbcDocumentStore documents = new JdbcDocumentStore(jdbc);
    final String tenant = "outbox-" + UUID.randomUUID();

    @BeforeEach
    void tenant() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.ONE, Set.of());
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM documents WHERE tenant_id = :t").param("t", tenant).update(); // cascades to outbox
        jdbc.sql("DELETE FROM tenants WHERE id = :t").param("t", tenant).update();
    }

    UUID job(String docId) {
        UUID documentId = documents.upsert(new DocumentUpsert(tenant, "c", docId, "T", "body " + docId, Set.of("eng")));
        return outbox.insert(documentId, "h".repeat(64));
    }

    /** Claims until nothing claimable is left, keeping only this test's jobs; never twice the same id overall. */
    List<OutboxEvent> drain(int batch) {
        List<OutboxEvent> all = new ArrayList<>();
        List<OutboxEvent> got;
        while (!(got = outbox.claimBatch(batch, LEASE)).isEmpty()) all.addAll(got);
        return all;
    }

    String status(UUID id) {
        return jdbc.sql("SELECT status FROM outbox WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void claimMovesPendingJobsToInProgressWithLeaseAndAttempt() {
        UUID a = job("a");
        List<OutboxEvent> claimed = drain(10);

        OutboxEvent mine = claimed.stream().filter(e -> e.id().equals(a)).findFirst().orElseThrow();
        assertThat(mine.attempts()).isEqualTo(1);
        assertThat(mine.contentHash()).isEqualTo("h".repeat(64));
        assertThat(status(a)).isEqualTo("IN_PROGRESS");
        Boolean leaseAhead = jdbc.sql("""
                        SELECT lease_until BETWEEN now() + interval '4 minutes' AND now() + interval '6 minutes'
                        FROM outbox WHERE id = :id""")
                .param("id", a).query(Boolean.class).single();
        assertThat(leaseAhead).isTrue();
        // Leased and not expired: not claimable again.
        assertThat(outbox.claimBatch(10, LEASE)).extracting(OutboxEvent::id).doesNotContain(a);
    }

    @Test
    void claimRespectsMaxAndTakesOldestFirst() {
        drain(50); // leftovers from other classes out of the way
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = job("d" + i);
            // created_at is now() of each insert's transaction; spread them so the order is unambiguous
            jdbc.sql("UPDATE outbox SET created_at = now() - :s * interval '1 second' WHERE id = :id")
                    .param("s", 10 - i).param("id", id).update();
            ids.add(id);
        }
        assertThat(outbox.claimBatch(3, LEASE)).extracting(OutboxEvent::id).containsExactly(ids.get(0), ids.get(1), ids.get(2));
        assertThat(outbox.claimBatch(3, LEASE)).extracting(OutboxEvent::id).containsExactly(ids.get(3), ids.get(4));
    }

    /**
     * D-47 amendment: the claim takes at most {@code max} rows whatever plan Postgres picks. These session settings
     * force the plan that re-scans the locking sub-select once per outer row (a nested-loop semi join with no hash,
     * sort, materialise or memoise step), which is what the planner chose intermittently in the full build. With
     * the old {@code WHERE id IN (SELECT … LIMIT n FOR UPDATE SKIP LOCKED)} form, each re-scan skipped the rows the
     * statement had already updated and returned new ones, so one call claimed every claimable row (all 20 here).
     * The materialised CTE runs the sub-select once.
     */
    @Test
    void claimNeverTakesMoreThanMaxEvenOnAPlanThatRescansTheSubSelect() {
        drain(100); // leftovers from other classes out of the way
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID id = job("r" + i);
            jdbc.sql("UPDATE outbox SET created_at = now() - :s * interval '1 second' WHERE id = :id")
                    .param("s", 100 - i).param("id", id).update();
            ids.add(id);
        }
        jdbc.sql("ANALYZE outbox").update();
        var pg = dev.raggateway.support.SharedContainers.postgres();
        var rescanning = new org.postgresql.ds.PGSimpleDataSource();
        rescanning.setUrl(pg.getJdbcUrl());
        rescanning.setUser(pg.getUsername());
        rescanning.setPassword(pg.getPassword());
        rescanning.setOptions("-c enable_hashjoin=off -c enable_mergejoin=off -c enable_hashagg=off "
                + "-c enable_sort=off -c enable_material=off -c enable_memoize=off");
        var store = new JdbcOutboxStore(org.springframework.jdbc.core.simple.JdbcClient.create(rescanning),
                new DataSourceTransactionManager(rescanning));

        for (int batch = 0; batch < 7; batch++) {
            List<OutboxEvent> got = store.claimBatch(3, LEASE);
            assertThat(got).as("batch %d", batch).hasSizeLessThanOrEqualTo(3);
            assertThat(got).extracting(OutboxEvent::id)
                    .containsExactlyElementsOf(ids.subList(Math.min(20, batch * 3), Math.min(20, batch * 3 + 3)));
        }
    }

    @Test
    void anExpiredLeaseIsClaimableAgainWithTheAttemptCounted() {
        UUID a = job("a");
        drain(10);
        jdbc.sql("UPDATE outbox SET lease_until = now() - interval '1 second' WHERE id = :id").param("id", a).update();

        List<OutboxEvent> again = drain(10);
        assertThat(again).filteredOn(e -> e.id().equals(a)).singleElement()
                .extracting(OutboxEvent::attempts).isEqualTo(2);
    }

    OutboxEvent claimed(List<OutboxEvent> events, UUID id) {
        return events.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void releaseMakesTheJobPendingWithTheErrorAndDoneOrDeadAreNeverClaimed() {
        UUID a = job("a");
        UUID b = job("b");
        UUID c = job("c");
        List<OutboxEvent> got = drain(10);

        assertThat(outbox.release(a, claimed(got, a).attempts(), "embedder down", Duration.ZERO)).isTrue();
        assertThat(outbox.markDone(b, claimed(got, b).attempts())).isTrue();
        assertThat(outbox.markDead(c, claimed(got, c).attempts(), "x".repeat(5_000)).dead()).isTrue();

        assertThat(status(a)).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT last_error FROM outbox WHERE id = :id").param("id", a).query(String.class).single())
                .isEqualTo("embedder down");
        assertThat(jdbc.sql("SELECT length(last_error) FROM outbox WHERE id = :id").param("id", c).query(Integer.class)
                .single()).isEqualTo(JdbcOutboxStore.MAX_ERROR_CHARS);
        assertThat(jdbc.sql("SELECT lease_until IS NULL FROM outbox WHERE id IN (:ids)").param("ids", List.of(b, c))
                .query(Boolean.class).list()).containsOnly(true);

        // Expire every lease: only the released job comes back.
        jdbc.sql("UPDATE outbox SET lease_until = now() - interval '1 second' WHERE id IN (:ids)")
                .param("ids", List.of(a, b, c)).update();
        assertThat(drain(10)).extracting(OutboxEvent::id).contains(a).doesNotContain(b, c);
        assertThat(status(b)).isEqualTo("DONE");
        assertThat(status(c)).isEqualTo("DEAD");
    }

    /** Seconds from the database's now() to the job's lease_until. */
    double secondsUntilLease(UUID id) {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM lease_until - now())::float8 FROM outbox WHERE id = :id")
                .param("id", id).query(Double.class).single();
    }

    /** Moves the database clock forward for this job only, as far as its retry time is concerned. */
    void timePasses(UUID id, Duration d) {
        jdbc.sql("UPDATE outbox SET lease_until = lease_until - :ms * interval '1 millisecond' WHERE id = :id")
                .param("ms", d.toMillis()).param("id", id).update();
    }

    @Test
    void aReleasedJobWaitsOutItsRetryTimeInLeaseUntilAndIsThenClaimedAgain() {
        UUID a = job("a");
        OutboxEvent first = claimed(drain(10), a);

        assertThat(outbox.release(a, first.attempts(), "embedder down", Duration.ofMinutes(2))).isTrue();

        assertThat(status(a)).isEqualTo("PENDING");
        assertThat(secondsUntilLease(a)).isBetween(110.0, 120.0);
        assertThat(drain(10)).extracting(OutboxEvent::id).doesNotContain(a);   // PENDING, but not due yet

        timePasses(a, Duration.ofSeconds(100));
        assertThat(drain(10)).extracting(OutboxEvent::id).doesNotContain(a);   // still ~20 s ahead
        timePasses(a, Duration.ofSeconds(20));
        assertThat(claimed(drain(10), a).attempts()).isEqualTo(2);              // due: claimed, attempt counted
    }

    @Test
    void fencing_aClaimantWhoseLeaseRanOutChangesNothing() {
        UUID a = job("a");
        OutboxEvent stale = claimed(drain(10), a);
        jdbc.sql("UPDATE outbox SET lease_until = now() - interval '1 second' WHERE id = :id").param("id", a).update();
        OutboxEvent current = claimed(drain(10), a);
        assertThat(current.attempts()).isEqualTo(stale.attempts() + 1);
        var before = jdbc.sql("SELECT status, attempts, lease_until, last_error FROM outbox WHERE id = :id")
                .param("id", a).query().singleRow();

        assertThat(outbox.markDone(a, stale.attempts())).isFalse();
        assertThat(outbox.release(a, stale.attempts(), "late", Duration.ZERO)).isFalse();
        assertThat(outbox.markDead(a, stale.attempts(), "late")).isEqualTo(new OutboxStore.DeadLetter(false, false));

        assertThat(jdbc.sql("SELECT status, attempts, lease_until, last_error FROM outbox WHERE id = :id")
                .param("id", a).query().singleRow()).isEqualTo(before);
        // The current owner's call goes through, once.
        assertThat(outbox.markDone(a, current.attempts())).isTrue();
        assertThat(outbox.markDone(a, current.attempts())).isFalse();
        assertThat(status(a)).isEqualTo("DONE");
    }

    @Test
    void markDeadFailsTheDocumentOnlyWhileItHoldsTheJobsHashAndIsQueued() {
        // Job for the version the document holds: dead, and the document is failed.
        UUID docA = documents.upsert(new DocumentUpsert(tenant, "c", "a", "T", "body a", Set.of("eng")));
        String hashA = documents.findById(docA).orElseThrow().contentHash();
        UUID a = outbox.insert(docA, hashA);
        // Job for an older version: dead, but a newer PUT owns the document.
        UUID docB = documents.upsert(new DocumentUpsert(tenant, "c", "b", "T", "body b", Set.of("eng")));
        UUID b = outbox.insert(docB, "0".repeat(64));
        List<OutboxEvent> got = drain(10);

        assertThat(outbox.markDead(a, claimed(got, a).attempts(), "boom")).isEqualTo(new OutboxStore.DeadLetter(true, true));
        assertThat(outbox.markDead(b, claimed(got, b).attempts(), "boom")).isEqualTo(new OutboxStore.DeadLetter(true, false));

        assertThat(documents.findById(docA).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(documents.findById(docB).orElseThrow().status()).isEqualTo(DocumentStatus.QUEUED);
    }

    @Test
    void unresolvedDeadJobsAreCountedUntilANewerJobOrADeleteResolvesThem() {
        long base = outbox.countUnresolvedDead();
        UUID docA = documents.upsert(new DocumentUpsert(tenant, "c", "a", "T", "body a", Set.of("eng")));
        UUID a = outbox.insert(docA, "h".repeat(64));
        UUID docB = documents.upsert(new DocumentUpsert(tenant, "c", "b", "T", "body b", Set.of("eng")));
        UUID b = outbox.insert(docB, "h".repeat(64));
        List<OutboxEvent> got = drain(10);
        outbox.markDead(a, claimed(got, a).attempts(), "boom");
        outbox.markDead(b, claimed(got, b).attempts(), "boom");
        assertThat(outbox.countUnresolvedDead()).isEqualTo(base + 2);

        outbox.insert(docA, "h".repeat(64));                       // a re-PUT queued a new job for a
        assertThat(outbox.countUnresolvedDead()).isEqualTo(base + 1);
        documents.delete(tenant, "c", "b");                          // b deleted: its jobs cascade
        assertThat(outbox.countUnresolvedDead()).isEqualTo(base);
    }

    @Test
    void concurrentClaimersNeverGetTheSameJob() throws Exception {
        int jobs = 300;
        Set<UUID> mine = new HashSet<>();
        for (int i = 0; i < jobs; i++) mine.add(job("doc-" + i));

        int claimers = 4; // each claimBatch call opens its own connection (no pool in AbstractPostgresTest)
        var start = new CyclicBarrier(claimers);
        ExecutorService pool = Executors.newFixedThreadPool(claimers);
        try {
            List<CompletableFuture<List<OutboxEvent>>> futures = new ArrayList<>();
            for (int t = 0; t < claimers; t++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return drain(7);
                }, pool));
            }
            List<UUID> claimed = new ArrayList<>();
            List<Integer> perClaimer = new ArrayList<>();
            for (var f : futures) {
                List<OutboxEvent> got = f.get(60, TimeUnit.SECONDS);
                perClaimer.add((int) got.stream().filter(e -> mine.contains(e.id())).count());
                got.forEach(e -> claimed.add(e.id()));
            }

            assertThat(claimed).doesNotHaveDuplicates();
            assertThat(claimed).containsAll(mine);
            assertThat(perClaimer.stream().mapToInt(Integer::intValue).sum()).isEqualTo(jobs);
            // Every job claimed exactly once, so every attempt counter is 1.
            assertThat(jdbc.sql("SELECT DISTINCT attempts FROM outbox WHERE id IN (:ids)")
                    .param("ids", List.copyOf(mine)).query(Integer.class).list()).containsExactly(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aClaimSkipsRowsLockedByAnotherClaimInsteadOfWaiting() throws Exception {
        drain(50);
        UUID first = job("first");
        jdbc.sql("UPDATE outbox SET created_at = now() - interval '1 minute' WHERE id = :id").param("id", first).update();
        UUID second = job("second");

        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var held = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        List<UUID> heldClaim = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Claimer 1 claims the oldest job inside a transaction it keeps open: the row stays locked.
            var holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                outbox.claimBatch(1, LEASE).forEach(e -> heldClaim.add(e.id()));
                held.countDown();
                try {
                    finish.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(heldClaim).containsExactly(first);

            // Claimer 2 (autocommit, its own connection) returns at once with the other job, not blocked by the lock.
            long t0 = System.nanoTime();
            List<OutboxEvent> other = CompletableFuture.supplyAsync(() -> outbox.claimBatch(10, LEASE))
                    .get(5, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
            assertThat(other).extracting(OutboxEvent::id).contains(second).doesNotContain(first);

            finish.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void claimArgumentsAreChecked() {
        assertThatThrownBy(() -> outbox.claimBatch(0, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.claimBatch(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
