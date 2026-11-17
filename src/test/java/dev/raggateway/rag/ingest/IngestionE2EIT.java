package dev.raggateway.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import dev.raggateway.rag.Embedder;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Ingestion end to end (t09, D-47): admin PUT over real HTTP, the real scheduled worker, the fake embedder, the real
 * chunk store on the shared Postgres. AC-t09-1, AC-t09-4 and AC-t09-15.
 *
 * <p>The only test class that enables the scheduled poller ({@code gateway.rag.worker.enabled=true}). The context
 * is closed after the class ({@link DirtiesContext}) so its poller cannot take later tests' outbox jobs. The
 * embedder is the fake behind a gate that blocks only inputs containing a marker, so a job of any other document
 * is never held. Row checks select by this test's own tenant and document ids.
 */
@Import(IngestionE2EIT.GatedEmbedderConfig.class)
@TestPropertySource(properties = {"gateway.rag.worker.enabled=true", "gateway.rag.worker.poll-delay=100ms"})
@DirtiesContext
class IngestionE2EIT extends AbstractIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String TENANT = "ingest-e2e-" + UUID.randomUUID();
    static final String ADMIN = "rgw_test_ingest_admin_" + UUID.randomUUID().toString().replace("-", "");
    static final String COLLECTION = "k8s";

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    GatedEmbedder gate;

    @Autowired
    DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @BeforeEach
    void seed() {
        gate.reset();
        TestData.insertTenant(jdbc, TENANT, 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, TENANT, ADMIN, Set.of(), true);
    }

    @AfterEach
    void open() {
        gate.reset();
    }

    @Test
    void theWorkerIsScheduledInThisContext() {
        assertThat(context.getBean(IndexingWorker.class)).isNotNull();
        assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .mapToInt(h -> h.getScheduledTasks().size()).sum()).isEqualTo(1);
    }

    @Test
    void ac_t09_1_and_ac_t09_4_putIsIndexedWithin10sAndEveryChunkCarriesTheDocumentsScope() throws Exception {
        String content = IntStream.range(0, 6)
                .mapToObj(i -> "## Section " + i + "\n\n" + "A Pod runs containers on a node. ".repeat(60))
                .collect(Collectors.joining("\n\n"));

        var put = put("concepts.workloads.pods", body("Pods", content, List.of("support", "eng")));
        assertThat(put.statusCode()).isEqualTo(202);
        assertThat(json(put).get("status").asText()).isEqualTo("queued");

        // AC-t09-1: indexed within 10 s, chunk count > 0, seen through GET.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(
                () -> "indexed".equals(json(get("concepts.workloads.pods")).get("status").asText()));
        JsonNode doc = json(get("concepts.workloads.pods"));
        int chunkCount = doc.get("chunk_count").asInt();
        assertThat(chunkCount).isGreaterThan(6);   // six sections, each longer than one chunk

        // AC-t09-4: every chunk row has the document's tenant, collection and groups; 768-value vectors.
        UUID documentId = jdbc.sql("SELECT id FROM documents WHERE tenant_id = ? AND collection_id = ? AND doc_id = ?")
                .params(TENANT, COLLECTION, "concepts.workloads.pods").query(UUID.class).single();
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT tenant_id, collection_id, doc_id, title, array_to_string(acl_groups, ',') AS groups,
                               vector_dims(embedding) AS dims, ordinal
                        FROM chunks WHERE document_id = ? ORDER BY ordinal""")
                .param(documentId).query().listOfRows();
        assertThat(rows).hasSize(chunkCount);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i))
                    .containsEntry("tenant_id", TENANT)
                    .containsEntry("collection_id", COLLECTION)
                    .containsEntry("doc_id", "concepts.workloads.pods")
                    .containsEntry("title", "Pods")
                    .containsEntry("groups", "eng,support")
                    .containsEntry("dims", 768)
                    .containsEntry("ordinal", i);
        }
        assertThat(jdbc.sql("SELECT status FROM outbox WHERE document_id = ?").param(documentId)
                .query(String.class).single()).isEqualTo("DONE");
    }

    @Test
    void ac_t09_15_noConnectionIsHeldWhileTheEmbedderRunsAndThreeGetsSucceed() throws Exception {
        CountDownLatch entered = gate.holdInputsContaining("GATE-MARKER");
        var put = put("blocked", body("Blocked", "GATE-MARKER. The worker embeds this document.", List.of("eng")));
        assertThat(put.statusCode()).isEqualTo(202);
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("worker reached the embedder").isTrue();

        // The worker is parked inside the embedder until released. If it held a connection, the count could never
        // reach 0; once it is 0 it must stay 0 while the worker stays parked.
        var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.getActiveConnections() == 0);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> pool.getActiveConnections() == 0);

        // Three concurrent GETs on the pool of 3 all succeed while the worker is still parked.
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<HttpResponse<String>>> gets = IntStream.range(0, 3)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await();
                            return get("blocked");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, threads)).toList();
            start.countDown();
            for (var f : gets) {
                HttpResponse<String> r = f.get(10, TimeUnit.SECONDS);
                assertThat(r.statusCode()).isEqualTo(200);
                assertThat(json(r).get("status").asText()).isEqualTo("queued");
            }
        }
        assertThat(gate.isHolding()).as("worker still parked during the GETs").isTrue();

        gate.reset();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> "indexed".equals(json(get("blocked")).get("status").asText()));
    }

    // ---- helpers ----

    String url(String docId) {
        return baseUrl() + "/v1/rag/collections/" + COLLECTION + "/documents/" + docId;
    }

    static String body(String title, String content, List<String> groups) throws Exception {
        return JSON.writeValueAsString(Map.of("title", title, "content", content, "acl_groups", groups));
    }

    HttpResponse<String> put(String docId, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url(docId))).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + ADMIN).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String docId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url(docId))).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + ADMIN).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatedEmbedderConfig {
        @Bean
        @Primary
        GatedEmbedder gatedEmbedder() {
            return new GatedEmbedder(new FakeEmbedder());
        }
    }

    /** The fake embedder; a call whose inputs contain the armed marker waits until {@link #reset()}. */
    static final class GatedEmbedder implements Embedder {
        private final FakeEmbedder delegate;
        private volatile String marker;
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);
        private volatile boolean holding;

        GatedEmbedder(FakeEmbedder delegate) {
            this.delegate = delegate;
        }

        CountDownLatch holdInputsContaining(String marker) {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            this.marker = marker;
            return entered;
        }

        boolean isHolding() {
            return holding;
        }

        void reset() {
            marker = null;
            release.countDown();
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            String m = marker;
            if (m != null && texts.stream().anyMatch(t -> t.contains(m))) {
                CountDownLatch r = release;
                holding = true;
                entered.countDown();
                try {
                    if (!r.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test never released the gate");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    holding = false;
                }
            }
            return delegate.embedDocuments(texts);
        }

        @Override
        public float[] embedQuery(String text) {
            return delegate.embedQuery(text);
        }
    }
}
