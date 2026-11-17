package dev.raggateway.rag.query;

import dev.raggateway.metrics.MetricNames;
import dev.raggateway.rag.PgVector;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.rag.query.ChunkSearchRepository.Row;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t12-5 (D-49 and its amendment): HNSW under a selective ACL filter. One collection of 5,000 chunks as dense
 * clustered vectors ({@link RagFixtures#near}; never the fake embedder, whose sparse vectors build a poorly connected
 * graph): 4,800 in group {@code eng} clustered around the question, 200 (4%) in group {@code rare} in a second
 * cluster further out. A caller with only {@code rare} asks.
 *
 * <p>The rare cluster's centre is the normalised sum of the question's cluster centre and a random one, so the rare
 * rows sit between the question's cluster and unrelated vectors. In strict distance order the scan reaches them right
 * after the 4,800 {@code eng} rows, before the rows other tests leave in the shared index (random directions, about
 * distance 1), so the count with the iterative scan depends on this test's rows and not on the suite's history.
 *
 * <ul>
 *   <li><b>SQL level</b>: with the HNSW index forced (session {@code enable_sort} and {@code enable_seqscan} off, so
 *       the index scan is the only ordered plan, as on a large table), {@link RetrievalSql#VECTOR} returns the full 50
 *       with {@code hnsw.iterative_scan = strict_order} and fewer with it off (ef_search 100 stays). Both counts are
 *       printed as {@code AC-t12-5 ...} lines.</li>
 *   <li><b>Repository level</b>: {@link ChunkSearchRepository#vector} returns 50 either way. With the iterative scan
 *       the HNSW pass is already full and no exact fallback is counted; without it the pass comes back short, the
 *       exact re-run fills the list in exact distance order, and {@code rag.retrieval.vector.exact_fallback} is 1.</li>
 * </ul>
 */
class FilteredHnswUnderfillTest extends AbstractPostgresTest {

    static final String TENANT = "t-underfill-" + UUID.randomUUID();
    static final String COLLECTION = "underfill";
    static final int ENG_ROWS = 4_800;
    static final int RARE_ROWS = 200;
    static final int LIMIT = 50;
    static final RagProperties.Retrieval ITERATIVE = RagProperties.Retrieval.defaults();
    static final RagProperties.Retrieval NO_ITERATIVE =
            new RagProperties.Retrieval(50, 60, 100, RagProperties.IterativeScan.OFF, 20_000);
    static float[] question;

    @BeforeAll
    static void seed() {
        TestData.insertTenant(jdbc, TENANT, 1000, BigDecimal.TEN, Set.of());
        float[] engCentre = RagFixtures.centre(31);
        float[] other = RagFixtures.centre(32);
        float[] rareCentre = new float[engCentre.length];
        for (int i = 0; i < rareCentre.length; i++) rareCentre[i] = engCentre[i] + other[i];
        rareCentre = RagFixtures.near(rareCentre, 0, 0);   // normalise only
        var embedder = new FakeEmbedder();
        UUID engDoc = RagFixtures.document(jdbc, TENANT, COLLECTION, "eng", "Eng", Set.of("eng"), embedder);
        UUID rareDoc = RagFixtures.document(jdbc, TENANT, COLLECTION, "rare", "Rare", Set.of("rare"), embedder);
        List<Object[]> rows = new ArrayList<>(ENG_ROWS + RARE_ROWS);
        for (int i = 0; i < ENG_ROWS; i++) {
            rows.add(row(engDoc, "eng", i, "eng " + i, "{eng}", RagFixtures.near(engCentre, 0.03, 1_000 + i)));
        }
        for (int i = 0; i < RARE_ROWS; i++) {
            rows.add(row(rareDoc, "rare", i, "rare " + i, "{rare}", RagFixtures.near(rareCentre, 0.03, 100_000 + i)));
        }
        new JdbcTemplate(dataSource).batchUpdate("""
                INSERT INTO chunks (document_id, tenant_id, collection_id, doc_id, title, ordinal, text, embedding,
                                    acl_groups)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), CAST(? AS text[]))""", rows);
        jdbc.sql("ANALYZE chunks").update();
        question = RagFixtures.near(engCentre, 0.03, 999);
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE tenant_id = ? AND collection_id = ?")
                .params(TENANT, COLLECTION).query(Long.class).single()).isEqualTo(ENG_ROWS + RARE_ROWS);
    }

    static Object[] row(UUID doc, String docId, int ordinal, String text, String groups, float[] vector) {
        return new Object[]{doc, TENANT, COLLECTION, docId, docId, ordinal, text, PgVector.literal(vector), groups};
    }

    @AfterAll
    static void cleanUp() {
        RagFixtures.deleteTenantDocuments(jdbc, TENANT);
        jdbc.sql("DELETE FROM tenants WHERE id = ?").param(TENANT).update();
    }

    /** Runs {@code work} in a read-only transaction with the HNSW scan as the only ordered plan. */
    static <T> T hnswForced(RagProperties.Retrieval settings, Function<JdbcClient, T> work) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setReadOnly(true);
        return tx.execute(status -> {
            var client = JdbcClient.create(
                    new SingleConnectionDataSource(DataSourceUtils.getConnection(dataSource), true));
            RetrievalSql.vectorSessionSettings(settings).forEach(s -> client.sql(s).update());
            client.sql("SET LOCAL enable_seqscan = off").update();
            client.sql("SET LOCAL enable_sort = off").update();
            return work.apply(client);
        });
    }

    static JdbcClient.StatementSpec bind(JdbcClient client, String sql) {
        return client.sql(sql).param("tenant", TENANT).param("collection", COLLECTION)
                .param("groups", new String[]{"rare"}).param("limit", LIMIT)
                .param("vector", PgVector.literal(question));
    }

    static List<String> vectorTexts(RagProperties.Retrieval settings) {
        return hnswForced(settings, c -> bind(c, RetrievalSql.VECTOR).query((rs, n) -> rs.getString("text")).list());
    }

    static String plan(RagProperties.Retrieval settings) {
        List<String> lines = hnswForced(settings, c -> bind(c, "EXPLAIN " + RetrievalSql.VECTOR)
                .query(String.class).list());
        return String.join("\n", lines);
    }

    List<String> truth() {
        return jdbc.sql("""
                        SELECT chunk_id::text FROM chunks
                         WHERE tenant_id = :t AND collection_id = :c AND acl_groups && '{rare}'
                         ORDER BY embedding <=> CAST(:q AS vector), chunk_id LIMIT 50""")
                .param("t", TENANT).param("c", COLLECTION).param("q", PgVector.literal(question))
                .query(String.class).list();
    }

    @Test
    void ac_t12_5_theVectorStatementFillsWithTheIterativeScanAndUnderFillsWithoutIt() {
        String onPlan = plan(ITERATIVE);
        String offPlan = plan(NO_ITERATIVE);
        List<String> on = vectorTexts(ITERATIVE);
        List<String> off = vectorTexts(NO_ITERATIVE);
        System.out.printf("AC-t12-5 SQL level, %d chunks, filter %d (%.1f%%), HNSW forced: "
                        + "iterative_scan=strict_order -> %d of %d rows, iterative_scan=off -> %d of %d rows%n",
                ENG_ROWS + RARE_ROWS, RARE_ROWS, 100.0 * RARE_ROWS / (ENG_ROWS + RARE_ROWS), on.size(), LIMIT,
                off.size(), LIMIT);

        for (String p : List.of(onPlan, offPlan)) {
            assertThat(p).contains("Index Scan using chunks_embedding_hnsw").doesNotContain("Sort")
                    .doesNotContain("Seq Scan");
        }
        assertThat(on).hasSize(LIMIT).allMatch(t -> t.startsWith("rare "));
        assertThat(off).hasSizeLessThan(LIMIT).allMatch(t -> t.startsWith("rare "));
    }

    @Test
    void ac_t12_5_throughTheRepositoryTheListIsFullEitherWayAndOnlyTheShortPassIsCounted() {
        DataSource hnswOnly = ChunkSearchRepositoryTest.hnswOnlyDataSource();
        var onMeters = new SimpleMeterRegistry();
        var offMeters = new SimpleMeterRegistry();

        List<Row> on = repository(hnswOnly, ITERATIVE, onMeters)
                .vector(TENANT, COLLECTION, Set.of("rare"), question, LIMIT);
        List<Row> off = repository(hnswOnly, NO_ITERATIVE, offMeters)
                .vector(TENANT, COLLECTION, Set.of("rare"), question, LIMIT);
        double onFallbacks = onMeters.counter(MetricNames.RETRIEVAL_EXACT_FALLBACK).count();
        double offFallbacks = offMeters.counter(MetricNames.RETRIEVAL_EXACT_FALLBACK).count();
        System.out.printf("AC-t12-5 repository level: iterative_scan=strict_order -> %d rows, %.0f exact fallback; "
                + "iterative_scan=off -> %d rows, %.0f exact fallback%n", on.size(), onFallbacks, off.size(), offFallbacks);

        assertThat(on).hasSize(LIMIT).allMatch(r -> r.text().startsWith("rare "));
        assertThat(onFallbacks).isZero();
        assertThat(off).extracting(Row::chunkId).containsExactlyElementsOf(truth());
        assertThat(offFallbacks).isEqualTo(1.0);
    }

    static ChunkSearchRepository repository(DataSource ds, RagProperties.Retrieval retrieval,
                                            SimpleMeterRegistry meters) {
        var rag = new RagProperties(null, null, 768, 20, null, null, 4, null, null, retrieval, null);
        return new ChunkSearchRepository(JdbcClient.create(ds), new DataSourceTransactionManager(ds), rag, meters);
    }
}
