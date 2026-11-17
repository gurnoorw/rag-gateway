package dev.raggateway.rag.query;

import dev.raggateway.rag.PgVector;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.rag.query.ChunkSearchRepository.Row;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.SharedContainers;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two legs of {@link ChunkSearchRepository} on the real schema: tenant, collection and group isolation even for
 * rows that would rank first, and the vector leg filling its candidate list through the HNSW index under a
 * selective filter because it applies the D-49 session settings in its own transaction.
 */
class ChunkSearchRepositoryTest extends AbstractPostgresTest {

    static final FakeEmbedder EMBEDDER = new FakeEmbedder();
    static final String QUESTION = "PodDisruptionBudget minAvailable";

    final String tenant = "t-search-" + UUID.randomUUID();
    final String otherTenant = "t-search-other-" + UUID.randomUUID();
    ChunkSearchRepository repository;

    static ChunkSearchRepository repository(DataSource ds, RagProperties.Retrieval retrieval) {
        var rag = new RagProperties(null, null, 768, 20, null, null, 4, null, null, retrieval, null);
        return new ChunkSearchRepository(JdbcClient.create(ds), new DataSourceTransactionManager(ds), rag);
    }

    @BeforeEach
    void seed() {
        repository = repository(dataSource, RagProperties.Retrieval.defaults());
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.TEN, Set.of());
        TestData.insertTenant(jdbc, otherTenant, 1000, BigDecimal.TEN, Set.of());
    }

    @AfterEach
    void cleanUp() {
        for (String t : List.of(tenant, otherTenant)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = ?").param(t).update();
        }
    }

    /**
     * One visible chunk, and three forbidden ones that are the best possible match for {@link #QUESTION}: the
     * question's words repeated (top keyword rank) and the question's own vector (cosine distance 0).
     */
    void seedVisibleAndForbidden() {
        RagFixtures.document(jdbc, tenant, "k8s", "concepts.pdb", "Disruptions", Set.of("eng"), EMBEDDER,
                "A PodDisruptionBudget limits voluntary disruptions of a replicated application.");
        forbidden(tenant, "k8s", "finance.pdb", "finance");   // same tenant and collection, a group the caller lacks
        forbidden(tenant, "other", "concepts.pdb", "eng");    // same tenant and group, another collection
        forbidden(otherTenant, "k8s", "concepts.pdb", "eng"); // another tenant, same collection, doc id and group
    }

    void forbidden(String tenantId, String collection, String docId, String group) {
        UUID doc = RagFixtures.document(jdbc, tenantId, collection, docId, "Forbidden", Set.of(group), EMBEDDER);
        RagFixtures.chunk(jdbc, doc, tenantId, collection, docId, "Forbidden", 0,
                "PodDisruptionBudget minAvailable PodDisruptionBudget minAvailable forbidden",
                new String[]{group}, EMBEDDER.embedQuery(QUESTION));
    }

    @Test
    void ac_t10_4_keywordLegNeverReturnsAnotherTenantsCollectionsOrGroupsChunkEvenWhenItWouldRankFirst() {
        seedVisibleAndForbidden();

        List<Row> rows = repository.keyword(tenant, "k8s", Set.of("eng"), QUESTION, 50);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.text()).startsWith("A PodDisruptionBudget limits");
            assertThat(r.docId()).isEqualTo("concepts.pdb");
            assertThat(r.title()).isEqualTo("Disruptions");
        });
        // Control: the forbidden chunk does rank first once the caller holds its group.
        assertThat(repository.keyword(tenant, "k8s", Set.of("eng", "finance"), QUESTION, 50).getFirst().text())
                .contains("forbidden");
        assertThat(repository.keyword(otherTenant, "k8s", Set.of("eng"), QUESTION, 50))
                .singleElement().extracting(Row::text).asString().contains("forbidden");
    }

    @Test
    void ac_t10_4_vectorLegNeverReturnsAnotherTenantsCollectionsOrGroupsChunkEvenAtDistanceZero() {
        seedVisibleAndForbidden();

        List<Row> rows = repository.vector(tenant, "k8s", Set.of("eng"), EMBEDDER.embedQuery(QUESTION), 50);

        assertThat(rows).singleElement().extracting(Row::text).asString().startsWith("A PodDisruptionBudget limits");
        assertThat(repository.vector(tenant, "k8s", Set.of("eng", "finance"), EMBEDDER.embedQuery(QUESTION), 50)
                .getFirst().text()).contains("forbidden");
    }

    @Test
    void ac_t10_2_keywordLegUsesOrSoAQuestionWithAWordFoundNowhereStillHits() {
        seedVisibleAndForbidden();

        assertThat(repository.keyword(tenant, "k8s", Set.of("eng"), "how does a PodDisruptionBudget qwxzzyv work", 50))
                .hasSize(1);
    }

    @Test
    void chunkIdsAreCanonicalUuidTextSoJavaAndPostgresOrderThemTheSame() {
        seedVisibleAndForbidden();
        String id = repository.keyword(tenant, "k8s", Set.of("eng"), QUESTION, 50).getFirst().chunkId();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    }

    /**
     * 1,560 chunks in one collection, 60 (~4%) in the caller's group: the shape D-49 measured. The planner on a
     * table this small would read the matching rows and sort them exactly, which never under-fills; a session with
     * {@code enable_sort} off (and no sequential scan) leaves the HNSW scan as the only ordered plan, as on a large
     * table. That is set on this test's own connections, never by the repository.
     */
    @Test
    void vectorLegUsesTheHnswIndexUnderASelectiveFilterAndReturnsTheFullCandidateCount() {
        UUID noise = RagFixtures.document(jdbc, tenant, "mixed", "noise", "Noise", Set.of("eng"), EMBEDDER);
        for (int i = 0; i < 1_500; i++) {
            RagFixtures.chunk(jdbc, noise, tenant, "mixed", "noise", "Noise", i, "noise " + i, new String[]{"eng"},
                    EMBEDDER.embedDocuments(List.of("scheduling pods onto nodes, copy " + i)).getFirst());
        }
        UUID rare = RagFixtures.document(jdbc, tenant, "mixed", "rare", "Rare", Set.of("rare"), EMBEDDER);
        for (int i = 0; i < 60; i++) {
            RagFixtures.chunk(jdbc, rare, tenant, "mixed", "rare", "Rare", i, "rare " + i, new String[]{"rare"},
                    EMBEDDER.embedDocuments(List.of("unrelated words number " + i)).getFirst());
        }
        jdbc.sql("ANALYZE chunks").update();
        DataSource hnswOnly = hnswOnlyDataSource();
        float[] question = EMBEDDER.embedQuery("scheduling pods");

        String plan = explainVector(hnswOnly, question, Set.of("rare"));
        List<Row> rows = repository(hnswOnly, RagProperties.Retrieval.defaults())
                .vector(tenant, "mixed", Set.of("rare"), question, 50);

        assertThat(plan).contains("Index Scan using chunks_embedding_hnsw").doesNotContain("Sort");
        assertThat(rows).hasSize(50).allMatch(r -> r.text().startsWith("rare "));

        // Control: the same repository without the iterative scan under-fills on this plan, so the full list
        // above comes from the settings the repository applies, not from an exact sort.
        var noIterativeScan = new RagProperties.Retrieval(50, 60, 100, RagProperties.IterativeScan.OFF, 20_000);
        assertThat(repository(hnswOnly, noIterativeScan).vector(tenant, "mixed", Set.of("rare"), question, 50))
                .hasSizeLessThan(50);
    }

    static DataSource hnswOnlyDataSource() {
        var pg = SharedContainers.postgres();
        var ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        ds.setOptions("-c enable_sort=off -c enable_seqscan=off");
        return ds;
    }

    /** EXPLAIN of the repository's statement, in a transaction with the repository's own session settings. */
    String explainVector(DataSource ds, float[] question, Set<String> groups) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        JdbcClient client = JdbcClient.create(ds);
        return tx.execute(status -> {
            RetrievalSql.vectorSessionSettings(RagProperties.Retrieval.defaults()).forEach(s -> client.sql(s).update());
            return String.join("\n", client.sql("EXPLAIN " + RetrievalSql.VECTOR)
                    .param("tenant", tenant).param("collection", "mixed")
                    .param("groups", groups.toArray(String[]::new)).param("limit", 50)
                    .param("vector", PgVector.literal(question))
                    .query(String.class).list());
        });
    }
}
