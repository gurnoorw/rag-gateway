package dev.raggateway.rag.query;

import dev.raggateway.rag.PgVector;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The t10 retrieval SQL contract ({@link RetrievalSql}, D-49) on the real schema: OR semantics and parsing edge cases
 * of the keyword leg (C19), tenant, collection and ACL predicates in both legs (C16, C17), and the vector leg's
 * session settings filling the candidate list through the HNSW index.
 */
class RetrievalSqlTest extends AbstractPostgresTest {

    static final FakeEmbedder EMBEDDER = new FakeEmbedder();
    final String tenant = "t-retrieval-" + UUID.randomUUID();
    final String otherTenant = "t-retrieval-other-" + UUID.randomUUID();

    @BeforeEach
    void seed() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.TEN, Set.of());
        TestData.insertTenant(jdbc, otherTenant, 1000, BigDecimal.TEN, Set.of());
        RagFixtures.document(jdbc, tenant, "k8s", "concepts.deployment", "Deployments", Set.of("eng"), EMBEDDER,
                "Set maxSurge to control how many extra Pods a rolling update may create.",
                "Run kubectl apply --dry-run=client to see what would change without changing it.");
        RagFixtures.document(jdbc, tenant, "k8s", "concepts.secrets", "Secrets", Set.of("security"), EMBEDDER,
                "A Secret holds a small amount of sensitive data such as a password, maxSurge-free.");
        RagFixtures.document(jdbc, tenant, "other", "concepts.deployment", "Deployments", Set.of("eng"), EMBEDDER,
                "Set maxSurge in the other collection.");
        RagFixtures.document(jdbc, otherTenant, "k8s", "concepts.deployment", "Deployments", Set.of("eng"), EMBEDDER,
                "Set maxSurge in the other tenant.");
    }

    @AfterEach
    void cleanUp() {
        for (String t : List.of(tenant, otherTenant)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = ?").param(t).update();
        }
    }

    List<String> keyword(String tenantId, String question, String... groups) {
        return jdbc.sql(RetrievalSql.KEYWORD).param("tenant", tenantId).param("collection", "k8s")
                .param("groups", groups).param("question", question).param("limit", 50)
                .query((rs, n) -> rs.getString("text")).list();
    }

    @Test
    void orSemanticsFindAChunkWhenOnlyOneWordMatches() {
        // AND semantics (websearch_to_tsquery) would need "xyzzyfoo" in the same chunk and find nothing (C19).
        assertThat(jdbc.sql("""
                SELECT count(*) FROM chunks WHERE tenant_id = :t AND tsv @@ websearch_to_tsquery('english', :q)""")
                .param("t", tenant).param("q", "xyzzyfoo maxSurge").query(Integer.class).single()).isZero();
        assertThat(keyword(tenant, "xyzzyfoo maxSurge", "eng")).singleElement().asString().contains("maxSurge to control");
    }

    @Test
    void stemmingMatchesOtherWordForms() {
        assertThat(keyword(tenant, "updating rolled updates", "eng")).anySatisfy(t -> assertThat(t).contains("rolling update"));
    }

    @Test
    void aQuestionOfStopWordsOnlyReturnsNoRowsAndNoError() {
        assertThat(keyword(tenant, "what is the", "eng")).isEmpty();
        assertThat(keyword(tenant, "", "eng")).isEmpty();
    }

    @Test
    void flagsAndHyphenatedTermsMatchVerbatim() {
        assertThat(keyword(tenant, "--dry-run=client", "eng")).singleElement().asString().contains("dry-run");
        assertThat(keyword(tenant, "dry run", "eng")).singleElement().asString().contains("dry-run");
    }

    @Test
    void operatorsTypedInTheQuestionAreNotOperators() {
        assertThat(keyword(tenant, "maxSurge & | ! <-> :* ' \" () \\\\", "eng")).hasSize(1);
        assertThat(keyword(tenant, "'); DROP TABLE chunks; --", "eng")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE tenant_id = ?").param(tenant).query(Integer.class)
                .single()).isEqualTo(4);
    }

    @Test
    void keywordLegKeepsToTheTenantCollectionAndGroups() {
        assertThat(keyword(tenant, "maxSurge", "eng")).containsExactly(
                "Set maxSurge to control how many extra Pods a rolling update may create.");
        assertThat(keyword(tenant, "maxSurge", "security")).singleElement().asString().contains("Secret");
        assertThat(keyword(tenant, "maxSurge", "eng", "security")).hasSize(2);
        assertThat(keyword(tenant, "maxSurge")).isEmpty();                    // no groups: nothing
        assertThat(keyword(otherTenant, "maxSurge", "eng")).containsExactly("Set maxSurge in the other tenant.");
    }

    List<String> vector(String tenantId, String collection, String question, int limit, RagProperties.Retrieval settings,
                        String... groups) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setReadOnly(true);
        return tx.execute(status -> {
            var conn = org.springframework.jdbc.core.simple.JdbcClient.create(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                            org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource), true));
            if (settings != null) RetrievalSql.vectorSessionSettings(settings).forEach(s -> conn.sql(s).update());
            // Force the HNSW index, as the planner picks it on a large table: on 1,560 rows it would rather read
            // the few matching rows and sort them exactly, which never under-fills. Without a sort node, the only
            // ordered plan is the HNSW scan.
            conn.sql("SET LOCAL enable_seqscan = off").update();
            conn.sql("SET LOCAL enable_sort = off").update();
            return conn.sql(RetrievalSql.VECTOR).param("tenant", tenantId).param("collection", collection)
                    .param("groups", groups).param("limit", limit)
                    .param("vector", PgVector.literal(EMBEDDER.embedQuery(question)))
                    .query((rs, n) -> rs.getString("text")).list();
        });
    }

    @Test
    void vectorLegKeepsToTheTenantCollectionAndGroups() {
        var settings = RagProperties.Retrieval.defaults();
        assertThat(vector(tenant, "k8s", "maxSurge rolling update", 50, settings, "eng")).hasSize(2)
                .noneMatch(t -> t.contains("Secret") || t.contains("other"));
        assertThat(vector(tenant, "k8s", "maxSurge", 50, settings)).isEmpty();
        assertThat(vector(otherTenant, "k8s", "maxSurge", 50, settings, "eng"))
                .containsExactly("Set maxSurge in the other tenant.");
    }

    @Test
    void vectorLegFillsTheCandidateListThroughTheIndexOnlyWithTheSessionSettings() {
        // One collection of 1,560 chunks; only 60 (~4%) carry the caller's group. A selective tenant or collection
        // filter can be planned as a btree scan plus an exact sort (no under-fill); a selective ACL filter cannot,
        // so the planner walks the HNSW index and filters afterwards, which is where rows go missing.
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

        List<String> withSettings = vector(tenant, "mixed", "scheduling pods", 50, RagProperties.Retrieval.defaults(), "rare");
        List<String> pgvectorDefaults = vector(tenant, "mixed", "scheduling pods", 50, null, "rare");

        assertThat(withSettings).hasSize(50).allMatch(t -> t.startsWith("rare "));
        assertThat(pgvectorDefaults).hasSizeLessThan(50);   // ef_search 40 and no iterative scan: under-fill
    }
}
