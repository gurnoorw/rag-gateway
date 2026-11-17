package dev.raggateway.rag.query;

import dev.raggateway.error.GatewayException;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievalResult;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.rag.query.ChunkSearchRepository.Row;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HybridRetriever} on the real repository and schema with the fake embedder (feature hashing, not a model:
 * vector-leg orders here say nothing about real retrieval quality; keyword-leg results are real).
 */
class HybridRetrieverDbTest extends AbstractPostgresTest {

    static final FakeEmbedder EMBEDDER = new FakeEmbedder();
    static final Set<String> ENG = Set.of("eng");

    final String tenant = "t-hybrid-" + UUID.randomUUID();
    final String otherTenant = "t-hybrid-other-" + UUID.randomUUID();
    ChunkSearchRepository repository;
    HybridRetriever retriever;

    @BeforeEach
    void seed() {
        repository = ChunkSearchRepositoryTest.repository(dataSource, RagProperties.Retrieval.defaults());
        retriever = new HybridRetriever(repository, EMBEDDER, RagProperties.defaults(), new SimpleMeterRegistry());
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.TEN, Set.of());
        TestData.insertTenant(jdbc, otherTenant, 1000, BigDecimal.TEN, Set.of());
        for (String t : List.of(tenant, otherTenant)) corpus(t);
        // In the caller's tenant and collection, but only for a group the caller lacks; it repeats the test
        // questions' words, so it would rank first in both legs if the group filter were missing.
        RagFixtures.document(jdbc, tenant, "k8s", "setup.billing", "Billing", Set.of("finance"), EMBEDDER,
                "Job backoffLimit retries Job backoffLimit retries --dry-run=client PodDisruptionBudget");
    }

    /** The same doc ids in both tenants (AC-t10-4). */
    void corpus(String t) {
        RagFixtures.document(jdbc, t, "k8s", "concepts.jobs", "Jobs", ENG, EMBEDDER,
                "A Job creates one or more Pods and retries them until a number of them succeed.",
                "Set backoffLimit to cap how many times a failing Job is retried before it is marked failed.");
        RagFixtures.document(jdbc, t, "k8s", "concepts.deployment", "Deployments", ENG, EMBEDDER,
                "A Deployment manages a ReplicaSet and rolls out new Pods gradually.",
                "Run kubectl apply --dry-run=client to preview a change before applying it.");
        RagFixtures.document(jdbc, t, "k8s", "concepts.pdb", "Disruptions", ENG, EMBEDDER,
                "A PodDisruptionBudget sets minAvailable so voluntary disruptions keep enough Pods running.");
        RagFixtures.document(jdbc, t, "k8s", "concepts.service", "Services", ENG, EMBEDDER,
                "A Service exposes a set of Pods behind one stable virtual address.");
        RagFixtures.document(jdbc, t, "k8s", "concepts.cronjob", "CronJobs", ENG, EMBEDDER,
                "A CronJob creates Jobs on a repeating schedule written in cron syntax.");
    }

    @AfterEach
    void cleanUp() {
        retriever.close();
        for (String t : List.of(tenant, otherTenant)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = ?").param(t).update();
        }
    }

    RetrievalResult retrieve(String question, RetrievalMode mode, int topK) {
        return retriever.retrieve(new RetrievalQuery(tenant, "k8s", question, ENG, topK, mode));
    }

    List<String> chunkIdsOf(String tenantId) {
        return jdbc.sql("SELECT chunk_id::text FROM chunks WHERE tenant_id = ?").param(tenantId)
                .query(String.class).list();
    }

    @Test
    void ac_t10_2_aQuestionWithAWordFoundNowhereStillGetsKeywordHits() {
        RetrievalResult result = retrieve("how does backoffLimit qwxzzyvplugh work", RetrievalMode.KEYWORD, 5);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().getFirst().text()).contains("backoffLimit");
    }

    @Test
    void ac_t10_3_aUniqueTokenInOneChunkIsInTheTopFiveInHybridAndKeywordModes() {
        for (RetrievalMode mode : List.of(RetrievalMode.HYBRID, RetrievalMode.KEYWORD)) {
            List<RetrievedChunk> top5 = retrieve("what does --dry-run=client do", mode, 5).chunks();
            assertThat(top5).as(mode.name()).extracting(RetrievedChunk::text)
                    .anySatisfy(t -> assertThat(t).contains("--dry-run=client"));
            assertThat(top5).as(mode.name()).anySatisfy(c -> assertThat(c.keywordRank()).isEqualTo(1));
        }
    }

    @Test
    void ac_t10_4_noResultCrossesTenantsCollectionsOrGroupsInAnyMode() {
        List<String> foreign = chunkIdsOf(otherTenant);
        String forbiddenGroupChunk = jdbc.sql("SELECT chunk_id::text FROM chunks WHERE tenant_id = ? AND doc_id = ?")
                .param(tenant).param("setup.billing").query(String.class).single();
        assertThat(foreign).hasSize(7);

        for (RetrievalMode mode : RetrievalMode.values()) {
            for (String question : List.of("Job backoffLimit retries", "--dry-run=client", "PodDisruptionBudget")) {
                List<RetrievedChunk> chunks = retrieve(question, mode, 20).chunks();
                assertThat(chunks).as(mode + " " + question).isNotEmpty()
                        .extracting(RetrievedChunk::chunkId)
                        .doesNotContainAnyElementsOf(foreign)
                        .doesNotContain(forbiddenGroupChunk);
            }
        }
        // Control: with the group, the forbidden chunk ranks first in the fused list.
        assertThat(retriever.retrieve(new RetrievalQuery(tenant, "k8s", "Job backoffLimit retries",
                Set.of("eng", "finance"), 5, RetrievalMode.HYBRID)).chunks().getFirst().chunkId())
                .isEqualTo(forbiddenGroupChunk);
        // And another collection of the same tenant is invisible.
        assertThat(retriever.retrieve(new RetrievalQuery(tenant, "other", "Job backoffLimit retries", ENG, 5,
                RetrievalMode.HYBRID)).chunks()).isEmpty();
    }

    @Test
    void ac_t10_9_theSameQueryTwiceReturnsTheSameOrder() {
        for (RetrievalMode mode : RetrievalMode.values()) {
            assertThat(retrieve("Pods retries schedule", mode, 20).chunks())
                    .as(mode.name()).isEqualTo(retrieve("Pods retries schedule", mode, 20).chunks());
        }
    }

    @Test
    void ac_t10_13_ranksMatchThePositionsInTheLegLists() {
        String question = "Job retries Pods";
        List<String> keywordIds = repository.keyword(tenant, "k8s", ENG, question, 50).stream()
                .map(Row::chunkId).toList();
        List<String> vectorIds = repository.vector(tenant, "k8s", ENG, EMBEDDER.embedQuery(question), 50).stream()
                .map(Row::chunkId).toList();
        assertThat(keywordIds).isNotEmpty();
        assertThat(vectorIds).hasSize(7);

        List<RetrievedChunk> chunks = retrieve(question, RetrievalMode.HYBRID, 20).chunks();

        assertThat(chunks).hasSize(7);   // the vector leg returns every visible chunk; the union is all of them
        for (RetrievedChunk c : chunks) {
            assertThat(c.keywordRank()).as(c.chunkId()).isEqualTo(keywordIds.indexOf(c.chunkId()) + 1);
            assertThat(c.vectorRank()).as(c.chunkId()).isEqualTo(vectorIds.indexOf(c.chunkId()) + 1);
        }
    }

    @Test
    void ac_t10_13_aStopWordsOnlyQuestionReturnsVectorResultsOnly() {
        RetrievalResult result = retrieve("what is the", RetrievalMode.HYBRID, 5);

        assertThat(result.modeUsed()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.chunks()).hasSize(5)
                .allSatisfy(c -> {
                    assertThat(c.keywordRank()).isZero();
                    assertThat(c.vectorRank()).isPositive();
                });
    }

    @Test
    void ac_t10_12_withTheEmbedderDownHybridReturnsTheKeywordResultsAndVectorFails() {
        Embedder down = new Embedder() {
            public int dimensions() {
                return 768;
            }

            public List<float[]> embedDocuments(List<String> texts) {
                throw new IllegalStateException("connection to Ollama refused");
            }

            public float[] embedQuery(String text) {
                throw new IllegalStateException("connection to Ollama refused");
            }
        };
        try (var degraded = new HybridRetriever(repository, down, RagProperties.defaults(), new SimpleMeterRegistry())) {
            String question = "backoffLimit retries";
            RetrievalResult hybrid = degraded.retrieve(new RetrievalQuery(tenant, "k8s", question, ENG, 5,
                    RetrievalMode.HYBRID));

            assertThat(hybrid.modeUsed()).isEqualTo(RetrievalMode.KEYWORD);
            assertThat(hybrid.chunks()).isEqualTo(retrieve(question, RetrievalMode.KEYWORD, 5).chunks());
            assertThat(hybrid.chunks()).isNotEmpty();
            assertThatThrownBy(() -> degraded.retrieve(new RetrievalQuery(tenant, "k8s", question, ENG, 5,
                    RetrievalMode.VECTOR))).isInstanceOf(GatewayException.class);
        }
    }
}
