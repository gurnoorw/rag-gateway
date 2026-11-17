package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ChunkText;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.OllamaEmbedJson;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcChunkStore} on the shared pgvector container (t09, D-47). Rows are scoped by this test's own tenant.
 */
class JdbcChunkStoreTest extends AbstractPostgresTest {

    final String tenant = "t-chunks-" + UUID.randomUUID();
    final List<Integer> batchRows = new CopyOnWriteArrayList<>();
    JdbcChunkStore store;
    IndexedDocument doc;

    @BeforeEach
    void setUp() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.TEN, Set.of());
        doc = insertDocument("concepts.jobs", "Jobs", Set.of("support", "eng"));
        DataSource counting = countingBatches(dataSource, batchRows);
        store = new JdbcChunkStore(new JdbcTemplate(counting), new DataSourceTransactionManager(counting));
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM documents WHERE tenant_id = ?").param(tenant).update();   // chunks cascade
        jdbc.sql("DELETE FROM tenants WHERE id = ?").param(tenant).update();
    }

    @Test
    void ac_t09_4_rowsCopyTenantCollectionDocIdTitleAndGroups() {
        store.replaceForDocument(doc, chunks(3), vectors(3));

        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT tenant_id, collection_id, doc_id, title, ordinal, text, acl_groups::text AS groups
                FROM chunks WHERE document_id = ? ORDER BY ordinal""").param(doc.id()).query().listOfRows();
        assertThat(rows).hasSize(3).allSatisfy(r -> {
            assertThat(r.get("tenant_id")).isEqualTo(tenant);
            assertThat(r.get("collection_id")).isEqualTo("docs");
            assertThat(r.get("doc_id")).isEqualTo("concepts.jobs");
            assertThat(r.get("title")).isEqualTo("Jobs");
            assertThat(r.get("groups")).isEqualTo("{eng,support}");
        });
        assertThat(rows).extracting(r -> r.get("text")).containsExactly("Chunk 0.", "Chunk 1.", "Chunk 2.");
    }

    @Test
    void setsStatusIndexedAndTheCountInTheSameTransaction() {
        store.replaceForDocument(doc, chunks(3), vectors(3));

        Map<String, Object> row = jdbc.sql("SELECT status, chunk_count FROM documents WHERE id = ?")
                .param(doc.id()).query().singleRow();
        assertThat(row).containsEntry("status", "indexed").containsEntry("chunk_count", 3);
        assertThat(store.countForDocument(doc.id())).isEqualTo(3);
    }

    @Test
    void vectorsRoundTripThroughTheTextForm() {
        float[] v = OllamaEmbedJson.vector(768, 7);
        v[0] = 1.0e-8f;          // exponent form must parse
        v[1] = -3.4028235e38f;   // largest magnitude float
        store.replaceForDocument(doc, chunks(1), List.of(v));

        String text = jdbc.sql("SELECT embedding::text FROM chunks WHERE document_id = ?").param(doc.id())
                .query(String.class).single();
        String[] parts = text.substring(1, text.length() - 1).split(",");
        assertThat(parts).hasSize(768);
        for (int i = 0; i < 768; i++) assertThat(Float.parseFloat(parts[i])).isEqualTo(v[i]);
    }

    @Test
    void cosineSearchFindsTheMatchingChunk() {
        List<float[]> vs = vectors(5);
        store.replaceForDocument(doc, chunks(5), vs);

        Integer nearest = jdbc.sql("""
                        SELECT ordinal FROM chunks WHERE tenant_id = ?
                        ORDER BY embedding <=> CAST(? AS vector) LIMIT 1""")
                .param(tenant).param(JdbcChunkStore.vectorLiteral(vs.get(3))).query(Integer.class).single();
        assertThat(nearest).isEqualTo(3);
    }

    @Test
    void insertsInJdbcBatchesOfAtMostOneHundredRows() {
        store.replaceForDocument(doc, chunks(250), vectors(250));

        assertThat(store.countForDocument(doc.id())).isEqualTo(250);
        assertThat(batchRows).containsExactly(100, 100, 50);
        assertThat(jdbc.sql("SELECT array_agg(ordinal ORDER BY ordinal) = array(SELECT generate_series(0, 249)) "
                + "FROM chunks WHERE document_id = ?").param(doc.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void replacingIsIdempotentAndRemovesOldChunks() {
        store.replaceForDocument(doc, chunks(5), vectors(5));
        store.replaceForDocument(doc, chunks(5), vectors(5));
        assertThat(store.countForDocument(doc.id())).isEqualTo(5);

        store.replaceForDocument(doc, chunks(2), vectors(2));
        assertThat(store.countForDocument(doc.id())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT chunk_count FROM documents WHERE id = ?").param(doc.id()).query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void aFailureMidInsertRollsEverythingBack() {
        store.replaceForDocument(doc, chunks(3), vectors(3));
        jdbc.sql("UPDATE documents SET status = 'queued' WHERE id = ?").param(doc.id()).update();   // a new version
        List<float[]> bad = new ArrayList<>(vectors(150));
        bad.set(120, new float[767]);   // the column rejects it in the second JDBC batch

        assertThatThrownBy(() -> store.replaceForDocument(doc, chunks(150), bad)).isInstanceOf(RuntimeException.class);

        assertThat(store.countForDocument(doc.id())).isEqualTo(3);
        Map<String, Object> row = jdbc.sql("SELECT status, chunk_count FROM documents WHERE id = ?")
                .param(doc.id()).query().singleRow();
        assertThat(row).containsEntry("status", "queued").containsEntry("chunk_count", 3);
    }

    @Test
    void mismatchedSizesAreRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> store.replaceForDocument(doc, chunks(2), vectors(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.countForDocument(doc.id())).isZero();
    }

    @Test
    void anotherDocumentsChunksAreUntouched() {
        IndexedDocument other = insertDocument("concepts.pods", "Pods", Set.of("eng"));
        store.replaceForDocument(other, chunks(2), vectors(2));
        store.replaceForDocument(doc, chunks(4), vectors(4));
        store.replaceForDocument(doc, chunks(1), vectors(1));

        assertThat(store.countForDocument(other.id())).isEqualTo(2);
        assertThat(store.countForDocument(doc.id())).isEqualTo(1);
    }

    @Test
    void anOldVersionNeverOverwritesANewerOne() {
        store.replaceForDocument(doc, chunks(3), vectors(3));
        // A newer PUT commits while the old version is still being embedded.
        jdbc.sql("UPDATE documents SET content_hash = ?, status = 'queued' WHERE id = ?")
                .params("1".repeat(64), doc.id()).update();

        assertThat(store.replaceForDocument(doc, chunks(5), vectors(5))).isFalse();

        assertThat(store.countForDocument(doc.id())).isEqualTo(3);
        assertThat(jdbc.sql("SELECT status FROM documents WHERE id = ?").param(doc.id()).query(String.class).single())
                .isEqualTo("queued");
    }

    @Test
    void aDeletedDocumentIsNotAnError() {
        jdbc.sql("DELETE FROM documents WHERE id = ?").param(doc.id()).update();
        assertThat(store.replaceForDocument(doc, chunks(2), vectors(2))).isFalse();
        assertThat(store.countForDocument(doc.id())).isZero();
    }

    @Test
    void vectorLiteralRejectsNonFiniteValues() {
        assertThat(JdbcChunkStore.vectorLiteral(new float[]{1f, -0.5f})).isEqualTo("[1.0,-0.5]");
        assertThatThrownBy(() -> JdbcChunkStore.vectorLiteral(new float[]{Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    IndexedDocument insertDocument(String docId, String title, Set<String> groups) {
        UUID id = jdbc.sql("""
                        INSERT INTO documents (tenant_id, collection_id, doc_id, title, content, acl_groups, content_hash)
                        VALUES (?, 'docs', ?, ?, 'content', CAST(? AS text[]), ?) RETURNING id""")
                .params(tenant, docId, title, groups.toArray(String[]::new), "0".repeat(64)).query(UUID.class).single();
        return new IndexedDocument(id, tenant, "docs", docId, title, "content", groups, "0".repeat(64),
                DocumentStatus.QUEUED, 0, Instant.now());
    }

    static List<ChunkText> chunks(int n) {
        return IntStream.range(0, n).mapToObj(i -> new ChunkText(i, "Chunk " + i + ".", 3)).toList();
    }

    static List<float[]> vectors(int n) {
        return IntStream.range(0, n).mapToObj(i -> OllamaEmbedJson.vector(768, 100 + i)).toList();
    }

    /** Wraps a DataSource so every {@code executeBatch} records how many rows were added to it. */
    static DataSource countingBatches(DataSource target, List<Integer> sink) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, args) -> {
                    Object result = invoke(target, m, args);
                    if (!(result instanceof Connection con)) return result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (cp, cm, cargs) -> {
                                Object r = invoke(con, cm, cargs);
                                if (!(r instanceof PreparedStatement ps)) return r;
                                AtomicInteger added = new AtomicInteger();
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (sp, sm, sargs) -> {
                                            if (sm.getName().equals("addBatch") && sargs == null) added.incrementAndGet();
                                            if (sm.getName().equals("executeBatch")) sink.add(added.getAndSet(0));
                                            return invoke(ps, sm, sargs);
                                        });
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
