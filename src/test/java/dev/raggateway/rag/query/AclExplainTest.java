package dev.raggateway.rag.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.rag.PgVector;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t12-6: the tenant, collection and ACL predicates sit inside the scan node of every retrieval statement
 * ({@link RetrievalSql#KEYWORD}, {@link RetrievalSql#VECTOR}, {@link RetrievalSql#VECTOR_EXACT}) under every plan
 * shape the planner can choose here (its own choice, the HNSW index forced, a bitmap scan, a sequential scan), and
 * no node above the scan filters rows. And no post-filter in code: the repository and the retriever return rows
 * exactly as the database ordered them, so the only filter is the one in SQL.
 *
 * <p>Reads {@code EXPLAIN (FORMAT JSON)} and walks the plan tree, so the check does not depend on the text layout.
 */
class AclExplainTest extends AbstractPostgresTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final FakeEmbedder EMBEDDER = new FakeEmbedder();
    static final String QUESTION = "how does backoffLimit cap the retries of a Job";
    static final Set<String> SCANS = Set.of("Seq Scan", "Index Scan", "Index Only Scan", "Bitmap Heap Scan");
    static final List<String> PREDICATES = List.of("tenant_id", "collection_id", "acl_groups");

    final String tenant = "t-explain-" + UUID.randomUUID();
    final String otherTenant = "t-explain-other-" + UUID.randomUUID();

    @BeforeEach
    void seed() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.TEN, Set.of());
        TestData.insertTenant(jdbc, otherTenant, 1000, BigDecimal.TEN, Set.of());
        RagFixtures.document(jdbc, tenant, "k8s", "concepts.jobs", "Jobs", Set.of("eng"), EMBEDDER,
                "A Job creates Pods and retries them until enough succeed.",
                "Set backoffLimit to cap the retries of a failing Job.",
                "A Job with backoffLimit 0 is never retried.");
        RagFixtures.document(jdbc, tenant, "k8s", "concepts.cronjob", "CronJobs", Set.of("eng", "sre"), EMBEDDER,
                "A CronJob creates a Job on a schedule; each Job keeps its own backoffLimit.");
        RagFixtures.document(jdbc, tenant, "k8s", "runbooks.billing", "Billing", Set.of("finance"), EMBEDDER,
                "backoffLimit retries Job backoffLimit retries Job cap");
        RagFixtures.document(jdbc, tenant, "other", "concepts.jobs", "Jobs", Set.of("eng"), EMBEDDER,
                "backoffLimit caps Job retries in another collection.");
        RagFixtures.document(jdbc, otherTenant, "k8s", "concepts.jobs", "Jobs", Set.of("eng"), EMBEDDER,
                "backoffLimit caps Job retries in another tenant.");
        jdbc.sql("ANALYZE chunks").update();
    }

    @AfterEach
    void cleanUp() {
        for (String t : List.of(tenant, otherTenant)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = ?").param(t).update();
        }
    }

    // ---- EXPLAIN ----

    /** Plan shapes: session settings that steer the planner to each kind of scan. */
    static final Map<String, List<String>> SHAPES = Map.of(
            "planner's choice", List.of(),
            "index forced, no sort", List.of("SET LOCAL enable_seqscan = off", "SET LOCAL enable_sort = off"),
            "bitmap scan", List.of("SET LOCAL enable_seqscan = off", "SET LOCAL enable_indexscan = off"),
            "sequential scan", List.of("SET LOCAL enable_indexscan = off", "SET LOCAL enable_bitmapscan = off"));

    static Stream<Arguments> statementsAndShapes() {
        Map<String, String> statements = Map.of("KEYWORD", RetrievalSql.KEYWORD, "VECTOR", RetrievalSql.VECTOR,
                "VECTOR_EXACT", RetrievalSql.VECTOR_EXACT);
        List<Arguments> out = new ArrayList<>();
        statements.forEach((name, sql) -> SHAPES.forEach((shape, settings) -> {
            // VECTOR_EXACT orders by an expression no index serves: it needs a sort, so "no sort" has no plan.
            if (!(name.equals("VECTOR_EXACT") && shape.equals("index forced, no sort"))) {
                out.add(Arguments.of(name, shape, sql, settings));
            }
        }));
        return out.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("statementsAndShapes")
    void ac_t12_6_tenantCollectionAndAclPredicatesAreInsideTheScanNode(String name, String shape, String sql,
                                                                     List<String> settings) throws Exception {
        JsonNode plan = explain(sql, settings);

        List<JsonNode> scans = new ArrayList<>();
        List<String> filtersAboveScans = new ArrayList<>();
        walk(plan, scans, filtersAboveScans);

        assertThat(scans).as(name + " plan:\n" + plan.toPrettyString()).isNotEmpty();
        for (JsonNode scan : scans) {
            assertThat(scan.get("Relation Name").asText()).isEqualTo("chunks");
            String conditions = conditions(scan);
            for (String predicate : PREDICATES) {
                assertThat(conditions).as(name + " / " + shape + ": " + predicate + " in "
                        + scan.get("Node Type").asText() + "\n" + plan.toPrettyString()).contains(predicate);
            }
            assertThat(conditions).contains(tenant);
        }
        assertThat(filtersAboveScans).as("no node above the scan filters rows").isEmpty();
        if (name.equals("VECTOR") && shape.equals("index forced, no sort")) {
            assertThat(scans).singleElement().satisfies(s ->
                    assertThat(s.get("Index Name").asText()).isEqualTo("chunks_embedding_hnsw"));
        }
        if (shape.equals("sequential scan")) {
            assertThat(scans).allSatisfy(s -> assertThat(s.get("Node Type").asText()).isEqualTo("Seq Scan"));
        }
    }

    JsonNode explain(String sql, List<String> settings) throws Exception {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setReadOnly(true);
        String json = tx.execute(status -> {
            var client = JdbcClient.create(
                    new SingleConnectionDataSource(DataSourceUtils.getConnection(dataSource), true));
            RetrievalSql.vectorSessionSettings(RagProperties.Retrieval.defaults()).forEach(s -> client.sql(s).update());
            settings.forEach(s -> client.sql(s).update());
            return client.sql("EXPLAIN (FORMAT JSON) " + sql)
                    .param("tenant", tenant).param("collection", "k8s").param("groups", new String[]{"eng"})
                    .param("limit", 50).param("question", QUESTION)
                    .param("vector", PgVector.literal(EMBEDDER.embedQuery(QUESTION)))
                    .query(String.class).single();
        });
        return JSON.readTree(json).get(0).get("Plan");
    }

    /** Collects scan nodes on a relation, and every filter on a node that is not such a scan. */
    static void walk(JsonNode node, List<JsonNode> scans, List<String> filtersAboveScans) {
        String type = node.get("Node Type").asText();
        if (SCANS.contains(type) && node.has("Relation Name")) {
            scans.add(node);
        } else {
            for (String key : List.of("Filter", "Join Filter", "One-Time Filter")) {
                if (node.has(key)) filtersAboveScans.add(type + ": " + node.get(key).asText());
            }
        }
        if (node.has("Plans")) {
            for (JsonNode child : node.get("Plans")) {
                // A bitmap heap scan's index children belong to the scan; walk them only to collect conditions.
                if (!type.equals("Bitmap Heap Scan")) walk(child, scans, filtersAboveScans);
            }
        }
    }

    /** The scan's own conditions, plus its bitmap index children's. */
    static String conditions(JsonNode scan) {
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("Filter", "Index Cond", "Recheck Cond")) {
            if (scan.has(key)) sb.append(scan.get(key).asText()).append('\n');
        }
        if (scan.has("Plans")) for (JsonNode child : scan.get("Plans")) sb.append(conditions(child));
        return sb.toString();
    }

    // ---- no post-filter in code ----

    @Test
    void ac_t12_6_theRepositoryReturnsTheDatabasesRowsInTheDatabasesOrder() {
        var repository = ChunkSearchRepositoryTest.repository(dataSource, RagProperties.Retrieval.defaults());
        float[] vector = EMBEDDER.embedQuery(QUESTION);

        List<String> keyword = repository.keyword(tenant, "k8s", Set.of("eng"), QUESTION, 50).stream()
                .map(ChunkSearchRepository.Row::chunkId).toList();
        List<String> vectorRows = repository.vector(tenant, "k8s", Set.of("eng"), vector, 50).stream()
                .map(ChunkSearchRepository.Row::chunkId).toList();

        List<String> keywordSql = jdbc.sql(RetrievalSql.KEYWORD).param("tenant", tenant).param("collection", "k8s")
                .param("groups", new String[]{"eng"}).param("question", QUESTION).param("limit", 50)
                .query((rs, n) -> rs.getString("chunk_id")).list();
        // Fewer matching rows than the limit, so the repository's answer is the exact statement's.
        List<String> exactSql = jdbc.sql(RetrievalSql.VECTOR_EXACT).param("tenant", tenant).param("collection", "k8s")
                .param("groups", new String[]{"eng"}).param("vector", PgVector.literal(vector)).param("limit", 50)
                .query((rs, n) -> rs.getString("chunk_id")).list();

        assertThat(keyword).hasSize(4).containsExactlyElementsOf(keywordSql);
        assertThat(vectorRows).hasSize(4).containsExactlyElementsOf(exactSql);
        // Positive control: a caller with finance too gets the billing chunk, which ranks first on keywords.
        assertThat(repository.keyword(tenant, "k8s", Set.of("eng", "finance"), QUESTION, 50)).hasSize(5);
    }

    @Test
    void ac_t12_6_theRetrieverPassesTheLegOrderThroughWithoutDroppingARow() {
        var repository = ChunkSearchRepositoryTest.repository(dataSource, RagProperties.Retrieval.defaults());
        try (var retriever = new HybridRetriever(repository, EMBEDDER, RagProperties.defaults(),
                new SimpleMeterRegistry())) {
            List<String> keywordOnly = retriever.retrieve(new RetrievalQuery(tenant, "k8s", QUESTION, Set.of("eng"),
                    20, RetrievalMode.KEYWORD)).chunks().stream().map(RetrievedChunk::chunkId).toList();
            List<String> hybrid = retriever.retrieve(new RetrievalQuery(tenant, "k8s", QUESTION, Set.of("eng"),
                    20, RetrievalMode.HYBRID)).chunks().stream().map(RetrievedChunk::chunkId).toList();

            assertThat(keywordOnly).containsExactlyElementsOf(repository.keyword(tenant, "k8s", Set.of("eng"),
                    QUESTION, 50).stream().map(ChunkSearchRepository.Row::chunkId).toList());
            List<String> union = new ArrayList<>(keywordOnly);
            repository.vector(tenant, "k8s", Set.of("eng"), EMBEDDER.embedQuery(QUESTION), 50).stream()
                    .map(ChunkSearchRepository.Row::chunkId).filter(id -> !union.contains(id)).forEach(union::add);
            assertThat(hybrid).containsExactlyInAnyOrderElementsOf(union);
        }
    }
}
