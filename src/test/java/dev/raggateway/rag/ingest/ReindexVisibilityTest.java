package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ChunkText;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t11-9: during a re-index a concurrent reader sees all-old or all-new chunks, never a mix. The swap's transaction
 * is paused just before its commit, after it has deleted the old rows and inserted the new ones; a reader on another
 * connection meanwhile sees exactly the old set (and the old chunk count), without waiting, and the new set once the
 * commit goes through. Rows scoped by this test's tenant.
 */
class ReindexVisibilityTest extends AbstractPostgresTest {

    final String tenant = "reindex-" + UUID.randomUUID();
    final JdbcDocumentStore documents = new JdbcDocumentStore(jdbc);
    final FakeEmbedder embedder = new FakeEmbedder();

    /** Parks every commit until {@link #resume} is opened. */
    static final class PausingTransactionManager extends DataSourceTransactionManager {
        final CountDownLatch atCommit = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);

        PausingTransactionManager() {
            super(dataSource);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            atCommit.countDown();
            try {
                if (!resume.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never resumed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            super.doCommit(status);
        }
    }

    @BeforeEach
    void seed() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.ONE, Set.of());
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM documents WHERE tenant_id = :t").param("t", tenant).update();
        jdbc.sql("DELETE FROM tenants WHERE id = :t").param("t", tenant).update();
    }

    List<ChunkText> pieces(String version, int n) {
        return IntStream.range(0, n).mapToObj(i -> new ChunkText(i, version + " chunk " + i, 3)).toList();
    }

    List<float[]> vectors(List<ChunkText> pieces) {
        return embedder.embedDocuments(pieces.stream().map(ChunkText::text).toList());
    }

    List<String> visibleTexts(UUID id) {
        return jdbc.sql("SELECT text FROM chunks WHERE document_id = :id ORDER BY ordinal").param("id", id)
                .query(String.class).list();
    }

    int visibleCount(UUID id) {
        return jdbc.sql("SELECT chunk_count FROM documents WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    @Test
    void ac_t11_9_aReaderDuringTheSwapSeesOnlyTheOldSetThenOnlyTheNewSet() throws Exception {
        UUID id = documents.upsert(new DocumentUpsert(tenant, "c", "d", "T", "old", Set.of("eng")));
        var plain = new JdbcChunkStore(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
        List<ChunkText> old = pieces("old", 3);
        assertThat(plain.replaceForDocument(documents.findById(id).orElseThrow(), old, vectors(old))).isTrue();

        documents.upsert(new DocumentUpsert(tenant, "c", "d", "T", "new", Set.of("eng")));
        IndexedDocument v2 = documents.findById(id).orElseThrow();
        var paused = new PausingTransactionManager();
        var swapping = new JdbcChunkStore(new JdbcTemplate(dataSource), paused);
        List<ChunkText> fresh = pieces("new", 4);
        List<float[]> freshVectors = vectors(fresh);

        try (var threads = Executors.newSingleThreadExecutor()) {
            Future<Boolean> swap = threads.submit(() -> swapping.replaceForDocument(v2, fresh, freshVectors));
            assertThat(paused.atCommit.await(10, TimeUnit.SECONDS)).as("swap reached its commit").isTrue();

            // Old rows deleted and new rows inserted, not committed: the reader sees the old set only, at once.
            assertThat(visibleTexts(id)).containsExactly("old chunk 0", "old chunk 1", "old chunk 2");
            assertThat(visibleCount(id)).isEqualTo(3);

            paused.resume.countDown();
            assertThat(swap.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(visibleTexts(id)).containsExactly("new chunk 0", "new chunk 1", "new chunk 2", "new chunk 3");
        assertThat(visibleCount(id)).isEqualTo(4);
    }
}
