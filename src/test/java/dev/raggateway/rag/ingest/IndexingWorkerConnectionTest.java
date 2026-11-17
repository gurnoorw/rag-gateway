package dev.raggateway.rag.ingest;

import com.zaxxer.hikari.HikariDataSource;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.rag.ingest.IngestFakes.CountingEmbedder;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.SharedContainers;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t09-15 at the worker level: while the embedder runs, the worker holds no database connection and no
 * transaction. A real Hikari pool of 3 (the test profile's size) and the real {@link JdbcChunkStore}; the outbox and
 * document stores are in-memory until the JDBC ones are merged. The same check through HTTP with the scheduled
 * worker is {@code IngestionE2EIT}.
 */
class IndexingWorkerConnectionTest extends AbstractPostgresTest {

    final String tenant = "t-conn-" + UUID.randomUUID();
    HikariDataSource pool;

    @BeforeEach
    void setUp() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.TEN, Set.of());
        var pg = SharedContainers.postgres();
        pool = new HikariDataSource();
        pool.setJdbcUrl(pg.getJdbcUrl());
        pool.setUsername(pg.getUsername());
        pool.setPassword(pg.getPassword());
        pool.setMaximumPoolSize(3);
        pool.setConnectionTimeout(1000);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        jdbc.sql("DELETE FROM documents WHERE tenant_id = ?").param(tenant).update();
        jdbc.sql("DELETE FROM tenants WHERE id = ?").param(tenant).update();
    }

    @Test
    void ac_t09_15_noConnectionOrTransactionIsHeldWhileTheEmbedderRuns() throws Exception {
        var fakes = new IngestFakes();
        String content = IntStream.range(0, 25).mapToObj(i -> "## Part " + i + "\n\nText " + i + ".")
                .reduce((a, b) -> a + "\n\n" + b).orElseThrow();   // 25 chunks: two embed calls
        IndexedDocument doc = insertDocument(content);
        fakes.documents.rows.put(doc.id(), doc);
        fakes.outbox.insert(doc.id(), doc.contentHash());

        CountDownLatch inEmbedder = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> activeDuringEmbed = new CopyOnWriteArrayList<>();
        List<Boolean> txDuringEmbed = new CopyOnWriteArrayList<>();
        CountingEmbedder embedder = fakes.new CountingEmbedder(new FakeEmbedder());
        embedder.duringCall = () -> {
            activeDuringEmbed.add(pool.getHikariPoolMXBean().getActiveConnections());
            txDuringEmbed.add(TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.getResourceMap().isEmpty());
            if (embedder.calls.size() == 1) {
                inEmbedder.countDown();
                await(release);
            }
        };
        var worker = new IndexingWorker(fakes.outbox, fakes.documents,
                new JdbcChunkStore(new JdbcTemplate(pool), new DataSourceTransactionManager(pool)),
                new HeadingParagraphSentenceChunker(new CharRatioTokenEstimator(), 400, 50, 400), embedder,
                4, Duration.ofMinutes(5), 20);
        // Warm the pool so a connection exists that the worker could have been holding.
        new JdbcTemplate(pool).queryForObject("SELECT 1", Integer.class);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var polling = threads.submit(worker::pollOnce);
            assertThat(inEmbedder.await(10, TimeUnit.SECONDS)).isTrue();

            // Blocked inside the embedder: nothing borrowed from the pool ...
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            // ... so three callers can each hold one of the 3 connections at the same time (1 s borrow timeout).
            CountDownLatch allHolding = new CountDownLatch(3);
            List<CompletableFuture<Integer>> readers = IntStream.range(0, 3).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                try (var con = pool.getConnection(); var st = con.createStatement()) {
                    allHolding.countDown();
                    await(allHolding);
                    var rs = st.executeQuery("SELECT 1");
                    rs.next();
                    return rs.getInt(1);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, threads)).toList();
            for (var r : readers) assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo(1);

            release.countDown();
            assertThat(polling.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(embedder.batchSizes()).containsExactly(20, 5);
        assertThat(activeDuringEmbed).containsExactly(0, 0);
        assertThat(txDuringEmbed).containsExactly(false, false);
        Map<String, Object> row = jdbc.sql("SELECT status, chunk_count FROM documents WHERE id = ?").param(doc.id())
                .query().singleRow();
        assertThat(row).containsEntry("status", "indexed").containsEntry("chunk_count", 25);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private IndexedDocument insertDocument(String content) {
        UUID id = jdbc.sql("""
                        INSERT INTO documents (tenant_id, collection_id, doc_id, title, content, acl_groups, content_hash)
                        VALUES (?, 'docs', 'big', 'Big', ?, '{eng}', ?) RETURNING id""")
                .params(tenant, content, "0".repeat(64)).query(UUID.class).single();
        return new IndexedDocument(id, tenant, "docs", "big", "Big", content, Set.of("eng"), "0".repeat(64),
                DocumentStatus.QUEUED, 0, Instant.now());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
