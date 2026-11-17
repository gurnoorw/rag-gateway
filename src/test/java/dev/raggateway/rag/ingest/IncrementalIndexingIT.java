package dev.raggateway.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.rag.ChunkText;
import dev.raggateway.rag.Chunker;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import dev.raggateway.rag.ingest.RecordingEmbedder.Gate;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.TestData;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incremental indexing end to end on the real database (t11, D-51): PUT and DELETE over HTTP, the real JDBC stores,
 * and workers built here on those stores with a {@link RecordingEmbedder}, driven by {@code pollOnce()} (the
 * scheduled poller is off under the test profile). AC-t11-1 to -8 and -10; AC-t11-9 is
 * {@link ReindexVisibilityTest}.
 *
 * <p>Time never passes by sleeping: a retry time or lease is moved by updating {@code lease_until} of this test's own
 * job, which is what the claim compares with the database clock. Every row check selects by this test's tenant or
 * document ids. Two workers and the test thread stay within the test pool of 3: no worker holds a connection while
 * it embeds (AC-t09-15).
 */
class IncrementalIndexingIT extends AbstractIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String TENANT = "incr-" + UUID.randomUUID();
    static final String OTHER_TENANT = "incr-other-" + UUID.randomUUID();
    static final String ADMIN = "rgw_test_incr_admin_" + UUID.randomUUID().toString().replace("-", "");
    static final String READER = "rgw_test_incr_reader_" + UUID.randomUUID().toString().replace("-", "");
    static final String OTHER_ADMIN = "rgw_test_incr_other_" + UUID.randomUUID().toString().replace("-", "");
    static final String COLLECTION = "k8s";

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    final RecordingEmbedder embedder = new RecordingEmbedder();

    @Autowired
    OutboxStore outbox;
    @Autowired
    DocumentStore documents;
    @Autowired
    ChunkStore chunkStore;
    @Autowired
    Chunker chunker;
    @Autowired
    IngestMetrics metrics;
    @Autowired
    MeterRegistry registry;
    @Autowired
    Retriever retriever;
    @Autowired
    WorkerRetryProperties retrySettings;
    @Autowired
    IndexingWorker appWorker;

    /** Swaps per document, counted around the real chunk store. */
    final Map<UUID, AtomicInteger> swaps = new ConcurrentHashMap<>();
    final List<Boolean> swapResults = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void seed() {
        TestData.insertTenant(jdbc, TENANT, 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, TENANT, ADMIN, Set.of(), true);
        TestData.insertKey(jdbc, TENANT, READER, Set.of("eng"), false);
        TestData.insertTenant(jdbc, OTHER_TENANT, 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, OTHER_TENANT, OTHER_ADMIN, Set.of(), true);
    }

    IndexingWorker worker(int claimBatch) {
        ChunkStore counting = new ChunkStore() {
            @Override
            public boolean replaceForDocument(IndexedDocument d, List<ChunkText> chunks, List<float[]> vectors) {
                boolean swapped = chunkStore.replaceForDocument(d, chunks, vectors);
                swapResults.add(swapped);
                if (swapped) swaps.computeIfAbsent(d.id(), k -> new AtomicInteger()).incrementAndGet();
                return swapped;
            }

            @Override
            public int countForDocument(UUID documentId) {
                return chunkStore.countForDocument(documentId);
            }
        };
        return new IndexingWorker(outbox, documents, counting, chunker, embedder, claimBatch, Duration.ofMinutes(5), 20,
                retrySettings, metrics);
    }

    /** Polls until a poll claims nothing. */
    static int drain(IndexingWorker w) {
        int claimed = 0;
        int n;
        while ((n = w.pollOnce()) > 0) claimed += n;
        return claimed;
    }

    // ---- HTTP helpers ----

    String url(String docId) {
        return baseUrl() + "/v1/rag/collections/" + COLLECTION + "/documents/" + docId;
    }

    static String body(String title, String content, List<String> groups) throws Exception {
        return JSON.writeValueAsString(Map.of("title", title, "content", content, "acl_groups", groups));
    }

    HttpResponse<String> put(String docId, String title, String content, String... groups) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url(docId))).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + ADMIN).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body(title, content, List.of(groups)))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    String putStatus(String docId, String title, String content, String... groups) throws Exception {
        var r = put(docId, title, content, groups);
        assertThat(r.statusCode()).isEqualTo(202);
        return json(r).get("status").asText();
    }

    HttpResponse<String> delete(String key, String docId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url(docId))).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key).DELETE().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    JsonNode get(String docId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url(docId))).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + ADMIN).GET().build();
        var r = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return json(r);
    }

    static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    // ---- row helpers (this test's tenant only) ----

    UUID documentId(String docId) {
        return jdbc.sql("SELECT id FROM documents WHERE tenant_id = ? AND collection_id = ? AND doc_id = ?")
                .params(TENANT, COLLECTION, docId).query(UUID.class).single();
    }

    List<Map<String, Object>> jobs(UUID documentId) {
        return jdbc.sql("""
                        SELECT id, status, attempts, content_hash, last_error, lease_until FROM outbox
                        WHERE document_id = ? ORDER BY created_at""")
                .param(documentId).query().listOfRows();
    }

    Map<String, Object> job(UUID jobId) {
        return jdbc.sql("SELECT status, attempts, lease_until, last_error FROM outbox WHERE id = ?")
                .param(jobId).query().singleRow();
    }

    List<String> chunkTexts(UUID documentId) {
        return jdbc.sql("SELECT text FROM chunks WHERE document_id = ? ORDER BY ordinal")
                .param(documentId).query(String.class).list();
    }

    List<String> chunkGroups(UUID documentId) {
        return jdbc.sql("SELECT array_to_string(acl_groups, ',') FROM chunks WHERE document_id = ? ORDER BY ordinal")
                .param(documentId).query(String.class).list();
    }

    int tenantJobs() {
        return jdbc.sql("SELECT count(*) FROM outbox o JOIN documents d ON d.id = o.document_id WHERE d.tenant_id = ?")
                .param(TENANT).query(Integer.class).single();
    }

    double counter(String result) {
        var c = registry.find(MetricNames.INGEST_DOCUMENTS).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    double deadGauge() {
        return registry.get(MetricNames.OUTBOX_DEAD).gauge().value();
    }

    double secondsUntilLease(UUID jobId) {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM lease_until - now())::float8 FROM outbox WHERE id = ?")
                .param(jobId).query(Double.class).single();
    }

    /** The database clock moves on by {@code d} for this job's retry time or lease (no sleep). */
    void timePasses(UUID jobId, Duration d) {
        jdbc.sql("UPDATE outbox SET lease_until = lease_until - ? * interval '1 millisecond' WHERE id = ?")
                .params(d.toMillis(), jobId).update();
    }

    void expireLease(UUID jobId) {
        jdbc.sql("UPDATE outbox SET lease_until = now() - interval '1 second' WHERE id = ?").param(jobId).update();
    }

    List<String> retrievedDocIds(String question, String group) {
        return retriever.retrieve(new RetrievalQuery(TENANT, COLLECTION, question, Set.of(group), 20,
                        RetrievalMode.HYBRID)).chunks().stream().map(RetrievedChunk::docId).distinct().toList();
    }

    static String sections(String word, int n) {
        return IntStream.range(0, n).mapToObj(i -> "## Part " + i + "\n\nThe " + word + " controller reconciles state "
                + i + ".").collect(Collectors.joining("\n\n"));
    }

    // ---- wiring ----

    @Test
    void retrySettingsComeFromApplicationYmlAndTheAppWorkerRefreshesTheDeadGauge() throws Exception {
        assertThat(retrySettings).isEqualTo(new WorkerRetryProperties(5, Duration.ofSeconds(30), Duration.ofMinutes(10)));
        assertThat(putStatus("wired", "Wired", "Body.", "eng")).isEqualTo("queued");
        UUID job = (UUID) jobs(documentId("wired")).getFirst().get("id");
        jdbc.sql("UPDATE outbox SET status = 'DEAD' WHERE id = ?").param(job).update();

        appWorker.pollOnce();   // the app's own worker bean: its metrics are the context's

        assertThat(deadGauge()).isEqualTo(1);
    }

    // ---- AC-t11-1 ----

    @Test
    void ac_t11_1_theSameDocumentTwiceIsEmbeddedOnceAndTheSecondPutIsUnchangedWithNoJob() throws Exception {
        IndexingWorker w = worker(4);
        double unchangedBefore = counter("unchanged");
        assertThat(putStatus("pods", "Pods", sections("pod", 3), "eng")).isEqualTo("queued");
        assertThat(putStatus("pods", "Pods", sections("pod", 3), "eng")).isEqualTo("unchanged");   // still queued
        drain(w);
        assertThat(get("pods").get("status").asText()).isEqualTo("indexed");
        int callsAfterFirst = embedder.calls.size();
        assertThat(callsAfterFirst).isPositive();

        var second = put("pods", "Pods", sections("pod", 3), "eng");

        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(json(second)).isEqualTo(JSON.readTree("""
                {"collection":"k8s","doc_id":"pods","status":"unchanged"}"""));
        assertThat(jobs(documentId("pods"))).hasSize(1);
        assertThat(drain(w)).isZero();
        assertThat(embedder.calls).hasSize(callsAfterFirst);
        assertThat(counter("unchanged") - unchangedBefore).isEqualTo(2);
    }

    // ---- AC-t11-2 ----

    @Test
    void ac_t11_2_afterAContentChangeNoChunkOfTheOldVersionRemains() throws Exception {
        IndexingWorker w = worker(4);
        putStatus("svc", "Services", sections("alpha", 5), "eng");
        drain(w);
        UUID id = documentId("svc");
        assertThat(chunkTexts(id)).hasSize(5).allSatisfy(t -> assertThat(t).contains("alpha"));

        assertThat(putStatus("svc", "Services", sections("beta", 3), "eng")).isEqualTo("queued");
        drain(w);

        List<String> texts = chunkTexts(id);
        assertThat(texts).hasSize(3).allSatisfy(t -> assertThat(t).contains("beta").doesNotContain("alpha"));
        assertThat(get("svc").get("chunk_count").asInt()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT ordinal FROM chunks WHERE document_id = ? ORDER BY ordinal").param(id)
                .query(Integer.class).list()).containsExactly(0, 1, 2);
    }

    // ---- AC-t11-3 ----

    @Test
    void ac_t11_3_aGroupChangeOnAnIndexedDocumentIsAppliedAtOnceWithoutEmbedding() throws Exception {
        IndexingWorker w = worker(4);
        putStatus("secrets", "Secrets", sections("secret", 4), "eng");
        drain(w);
        UUID id = documentId("secrets");
        int calls = embedder.calls.size();
        assertThat(retrievedDocIds("secret controller", "eng")).contains("secrets");

        var r = put("secrets", "Secrets", sections("secret", 4), "security", "sre");

        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(json(r).get("status").asText()).isEqualTo("acl_updated");
        // At once: no worker run in between.
        assertThat(chunkGroups(id)).hasSize(4).containsOnly("security,sre");
        JsonNode doc = get("secrets");
        assertThat(doc.get("acl_groups").toString()).isEqualTo("[\"security\",\"sre\"]");
        assertThat(doc.get("status").asText()).isEqualTo("indexed");
        assertThat(doc.get("content_hash").asText())
                .isEqualTo(DocumentHash.of("Secrets", sections("secret", 4), Set.of("security", "sre")));
        assertThat(jobs(id)).hasSize(1);
        assertThat(drain(w)).isZero();
        assertThat(embedder.calls).hasSize(calls);
        assertThat(retrievedDocIds("secret controller", "eng")).doesNotContain("secrets");
        assertThat(retrievedDocIds("secret controller", "sre")).contains("secrets");
    }

    @Test
    void ac_t11_3_theSameGroupChangeOnADocumentStillQueuedQueuesAFullJob() throws Exception {
        IndexingWorker w = worker(4);
        putStatus("cm", "ConfigMaps", sections("config", 2), "eng");

        assertThat(putStatus("cm", "ConfigMaps", sections("config", 2), "sre")).isEqualTo("queued");

        UUID id = documentId("cm");
        assertThat(jobs(id)).hasSize(2);
        drain(w);
        assertThat(chunkGroups(id)).hasSize(2).containsOnly("sre");
        assertThat(embedder.titlesPerCall()).containsExactly("ConfigMaps");   // the first job was stale-skipped
        assertThat(jobs(id)).extracting(j -> j.get("status")).containsOnly("DONE");
    }

    // ---- AC-t11-4 ----

    @Test
    void ac_t11_4_reloading300DocumentsWith10ChangedEmbedsExactlyThe10() throws Exception {
        IndexingWorker w = worker(50);
        for (int i = 0; i < 300; i++) {
            assertThat(putStatus("doc-" + i, "Doc " + i, "Document " + i + " explains topic " + i + ".", "eng"))
                    .isEqualTo("queued");
        }
        drain(w);
        assertThat(embedder.titles()).hasSize(300);
        embedder.clear();

        Map<String, Integer> outcomes = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            String content = i % 30 == 0 ? "Document " + i + " was rewritten." : "Document " + i + " explains topic " + i + ".";
            outcomes.merge(putStatus("doc-" + i, "Doc " + i, content, "eng"), 1, Integer::sum);
        }
        drain(w);

        assertThat(outcomes).isEqualTo(Map.of("unchanged", 290, "queued", 10));
        assertThat(embedder.titlesPerCall()).hasSize(10).containsExactlyInAnyOrderElementsOf(
                IntStream.range(0, 300).filter(i -> i % 30 == 0).mapToObj(i -> "Doc " + i).toList());
        assertThat(tenantJobs()).isEqualTo(310);
    }

    // ---- AC-t11-5 ----

    @Test
    void ac_t11_5_twoWorkersPollingAtOnceProcessEachOf50JobsExactlyOnce() throws Exception {
        for (int i = 0; i < 50; i++) putStatus("j-" + i, "Job " + i, "Job " + i + " body.", "eng");
        IndexingWorker a = worker(4);
        IndexingWorker b = worker(4);
        var start = new CyclicBarrier(2);

        try (var threads = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> polls = new ArrayList<>();
            for (IndexingWorker w : List.of(a, b)) {
                polls.add(threads.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return drain(w);
                }));
            }
            int claimed = 0;
            for (var f : polls) claimed += f.get(60, TimeUnit.SECONDS);
            assertThat(claimed).isEqualTo(50);
        }

        assertThat(embedder.titlesPerCall()).hasSize(50).doesNotHaveDuplicates();
        assertThat(swaps).hasSize(50).allSatisfy((doc, n) -> assertThat(n.get()).isEqualTo(1));
        assertThat(jdbc.sql("""
                        SELECT o.status || ':' || o.attempts FROM outbox o JOIN documents d ON d.id = o.document_id
                        WHERE d.tenant_id = ?""").param(TENANT).query(String.class).list())
                .hasSize(50).containsOnly("DONE:1");
    }

    // ---- AC-t11-6 ----

    @Test
    void ac_t11_6_afterALeaseExpiryAnotherWorkerTakesTheJobAndTheFirstWorkersLateMarkDoneChangesNothing()
            throws Exception {
        putStatus("lease", "Lease", "A job whose first worker stalls.", "eng");
        UUID doc = documentId("lease");
        UUID jobId = (UUID) jobs(doc).getFirst().get("id");
        IndexingWorker first = worker(1);
        IndexingWorker second = worker(1);
        Gate firstGate = embedder.gateNextCall();
        Gate secondGate = embedder.gateNextCall();

        try (var threads = Executors.newFixedThreadPool(2)) {
            Future<Integer> firstPoll = threads.submit(first::pollOnce);
            assertThat(firstGate.awaitEntered()).isTrue();
            expireLease(jobId);                                    // the first worker "crashed" as far as the lease goes
            Future<Integer> secondPoll = threads.submit(second::pollOnce);
            assertThat(secondGate.awaitEntered()).isTrue();
            Map<String, Object> owned = job(jobId);
            assertThat(owned).containsEntry("status", "IN_PROGRESS").containsEntry("attempts", 2);

            firstGate.open();                                      // the first worker wakes up and finishes late
            assertThat(firstPoll.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(job(jobId)).isEqualTo(owned);               // its markDone(attempt 1) touched nothing

            secondGate.open();
            assertThat(secondPoll.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(job(jobId)).containsEntry("status", "DONE").containsEntry("attempts", 2);
        assertThat(get("lease").get("status").asText()).isEqualTo("indexed");
        assertThat(chunkTexts(doc)).containsExactly("A job whose first worker stalls.");
    }

    @Test
    void ac_t11_6_aFirstWorkerThatFailsAfterItsLeaseRanOutDoesNotReleaseTheFinishedJob() throws Exception {
        putStatus("lease2", "Lease", "Another stalled job.", "eng");
        UUID jobId = (UUID) jobs(documentId("lease2")).getFirst().get("id");
        IndexingWorker first = worker(1);
        Gate firstGate = embedder.gateNextCall();
        double retriedBefore = counter("retried");

        try (var threads = Executors.newSingleThreadExecutor()) {
            Future<Integer> firstPoll = threads.submit(first::pollOnce);
            assertThat(firstGate.awaitEntered()).isTrue();
            expireLease(jobId);
            assertThat(worker(1).pollOnce()).isEqualTo(1);         // a second worker claims and finishes it
            Map<String, Object> done = job(jobId);
            assertThat(done).containsEntry("status", "DONE").containsEntry("attempts", 2);

            firstGate.openAndFail();                               // the late worker fails and tries to release
            firstPoll.get(10, TimeUnit.SECONDS);
            assertThat(job(jobId)).isEqualTo(done);                // still DONE: not back to PENDING, no error
        }
        assertThat(counter("retried")).isEqualTo(retriedBefore);
        assertThat(worker(1).pollOnce()).isZero();
    }

    // ---- AC-t11-7 ----

    @Test
    void ac_t11_7_aJobThatAlwaysFailsBacksOffThenIsDeadTheDocumentFailedAndARePutQueuesItAgain() throws Exception {
        IndexingWorker w = worker(4);
        embedder.failWhen = inputs -> true;
        putStatus("broken", "Broken", "This document never embeds.", "eng");
        UUID doc = documentId("broken");
        UUID jobId = (UUID) jobs(doc).getFirst().get("id");
        double deadBefore = counter("dead");

        Duration[] backoff = {Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4)};
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(w.pollOnce()).as("attempt %d is claimed", attempt).isEqualTo(1);
            Duration wait = backoff[attempt - 1];
            assertThat(job(jobId)).containsEntry("status", "PENDING").containsEntry("attempts", attempt);
            assertThat(secondsUntilLease(jobId)).as("retry time after attempt %d", attempt)
                    .isBetween(wait.toSeconds() - 5.0, (double) wait.toSeconds());
            assertThat(job(jobId).get("last_error").toString()).contains("HTTP 500");
            timePasses(jobId, wait.minusSeconds(5));
            assertThat(w.pollOnce()).as("not due 5 s before its retry time").isZero();
            timePasses(jobId, Duration.ofSeconds(5));
        }
        assertThat(deadGauge()).isZero();

        assertThat(w.pollOnce()).isEqualTo(1);                     // attempt 5

        assertThat(job(jobId)).containsEntry("status", "DEAD").containsEntry("attempts", 5)
                .containsEntry("lease_until", null);
        assertThat(get("broken").get("status").asText()).isEqualTo("failed");
        assertThat(counter("dead") - deadBefore).isEqualTo(1);
        assertThat(deadGauge()).isEqualTo(1);
        assertThat(metrics.deadJobs()).isEqualTo(1);
        assertThat(w.pollOnce()).isZero();                         // a dead job is never claimed

        embedder.failWhen = inputs -> false;
        assertThat(putStatus("broken", "Broken", "This document never embeds.", "eng")).isEqualTo("queued");
        assertThat(jobs(doc)).hasSize(2);
        assertThat(get("broken").get("status").asText()).isEqualTo("queued");
        assertThat(w.pollOnce()).isEqualTo(1);
        assertThat(get("broken").get("status").asText()).isEqualTo("indexed");
        assertThat(deadGauge()).isZero();                          // handled: the newer job resolves the dead one
    }

    // ---- AC-t11-8 ----

    @Test
    void ac_t11_8_twoQuickPutsEndWithV2WhenTheV1JobRunsFirst() throws Exception {
        IndexingWorker w = worker(1);
        double staleBefore = counter("stale_skipped");
        putStatus("race", "Race", "Version one text.", "eng");
        putStatus("race", "Race", "Version two text.", "eng");
        UUID doc = documentId("race");

        assertThat(w.pollOnce()).isEqualTo(1);                     // the v1 job (oldest): stale, not embedded
        assertThat(embedder.calls).isEmpty();
        assertThat(w.pollOnce()).isEqualTo(1);                     // the v2 job

        assertThat(chunkTexts(doc)).containsExactly("Version two text.");
        assertThat(counter("stale_skipped") - staleBefore).isEqualTo(1);
        assertThat(jobs(doc)).extracting(j -> j.get("status")).containsOnly("DONE");
    }

    @Test
    void ac_t11_8_twoQuickPutsEndWithV2WhenTheV2JobRunsFirst() throws Exception {
        IndexingWorker w = worker(1);
        putStatus("race", "Race", "Version one text.", "eng");
        putStatus("race", "Race", "Version two text.", "eng");
        UUID doc = documentId("race");
        UUID v1Job = (UUID) jobs(doc).getFirst().get("id");
        jdbc.sql("UPDATE outbox SET created_at = now() + interval '1 minute' WHERE id = ?").param(v1Job).update();

        assertThat(w.pollOnce()).isEqualTo(1);                     // v2 first
        assertThat(chunkTexts(doc)).containsExactly("Version two text.");
        assertThat(w.pollOnce()).isEqualTo(1);                     // then v1: stale

        assertThat(chunkTexts(doc)).containsExactly("Version two text.");
        assertThat(embedder.calls).hasSize(1);
        assertThat(get("race").get("status").asText()).isEqualTo("indexed");
    }

    @Test
    void ac_t11_8_aV2PutThatLandsWhileV1IsEmbeddedMakesTheV1SwapReturnFalse() throws Exception {
        IndexingWorker w = worker(1);
        putStatus("race", "Race", "Version one text.", "eng");
        UUID doc = documentId("race");
        Gate v1 = embedder.gateNextCall();

        try (var threads = Executors.newSingleThreadExecutor()) {
            Future<Integer> v1Poll = threads.submit(w::pollOnce);
            assertThat(v1.awaitEntered()).isTrue();
            putStatus("race", "Race", "Version two text.", "eng"); // commits while v1 is being embedded
            v1.open();
            v1Poll.get(10, TimeUnit.SECONDS);
        }
        assertThat(swapResults).containsExactly(false);
        assertThat(chunkTexts(doc)).isEmpty();

        assertThat(w.pollOnce()).isEqualTo(1);
        assertThat(chunkTexts(doc)).containsExactly("Version two text.");
        assertThat(swapResults).containsExactly(false, true);
        assertThat(jobs(doc)).extracting(j -> j.get("status")).containsOnly("DONE");
    }

    // ---- AC-t11-10 ----

    @Test
    void ac_t11_10_deleteRemovesTheDocumentItsChunksAndJobsIsIdempotentAndAdminOnly() throws Exception {
        IndexingWorker w = worker(4);
        putStatus("gone", "Ingress", sections("ingress", 3), "eng");
        drain(w);
        putStatus("gone", "Ingress", sections("ingress", 4), "eng");   // a pending job too
        UUID doc = documentId("gone");
        assertThat(retrievedDocIds("ingress controller", "eng")).contains("gone");
        double deletedBefore = counter("deleted");

        assertThat(delete(READER, "gone").statusCode()).isEqualTo(403);
        assertThat(chunkTexts(doc)).hasSize(3);
        assertThat(delete(OTHER_ADMIN, "gone").statusCode()).isEqualTo(204);   // not there for another tenant
        assertThat(chunkTexts(doc)).hasSize(3);

        var first = delete(ADMIN, "gone");

        assertThat(first.statusCode()).isEqualTo(204);
        assertThat(first.body()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM documents WHERE id = ?").param(doc).query(Integer.class).single()).isZero();
        assertThat(chunkTexts(doc)).isEmpty();
        assertThat(jobs(doc)).isEmpty();
        assertThat(delete(ADMIN, "gone").statusCode()).isEqualTo(204);
        assertThat(counter("deleted") - deletedBefore).isEqualTo(1);
        assertThat(retrievedDocIds("ingress controller", "eng")).doesNotContain("gone");
        var getAfter = http.send(HttpRequest.newBuilder(URI.create(url("gone"))).header("Authorization", "Bearer " + ADMIN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(getAfter.statusCode()).isEqualTo(404);
    }

    @Test
    void ac_t11_10_aJobInFlightWhenItsDocumentIsDeletedEndsHarmlessly() throws Exception {
        IndexingWorker w = worker(1);
        putStatus("inflight", "Inflight", "Deleted while it is embedded.", "eng");
        UUID doc = documentId("inflight");
        Gate gate = embedder.gateNextCall();

        try (var threads = Executors.newSingleThreadExecutor()) {
            Future<Integer> poll = threads.submit(w::pollOnce);
            assertThat(gate.awaitEntered()).isTrue();
            assertThat(delete(ADMIN, "inflight").statusCode()).isEqualTo(204);
            gate.open();
            assertThat(poll.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(swapResults).containsExactly(false);
        assertThat(chunkTexts(doc)).isEmpty();
        assertThat(jobs(doc)).isEmpty();
    }

    @Test
    void deleteWithAnInvalidIdIs400() throws Exception {
        var r = delete(ADMIN, "-bad");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").get("message").asText()).contains("document id");
    }
}
