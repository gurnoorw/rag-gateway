package dev.raggateway.rag.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.auth.JdbcApiKeyAuthenticator;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Tenant;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.GatewayPipeline;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The t12 leakage suite (D-51) on the whole application: real {@link HybridRetriever} and SQL, the real pipeline and
 * cache on the shared Redis, WireMock playing Claude and capturing every prompt. A leak is looked for at four levels:
 * <ol>
 *   <li>the chunk ids the caller was shown ({@code sources} and {@code citations}), counted by {@link AclAudit},
 *       which re-derives visibility from the chunk rows and does not reuse the retrieval predicates;</li>
 *   <li>the prompt sent upstream: no restricted text, title or document id in it;</li>
 *   <li>the response's {@code sources} and {@code citations};</li>
 *   <li>the cache: another key's cached answer is never served across groups.</li>
 * </ol>
 * Every leakage assertion has a positive control: the same question from a caller who holds the group does retrieve
 * the restricted chunk, so a test cannot pass because retrieval returned nothing. Fixtures are inserted directly
 * ({@link RagFixtures}) under this test's own tenants, with the app's fake embedder; the base class truncates every
 * table and flushes Redis before each test. Leakage checks send the cache-bypass header so every ask reaches WireMock
 * and its prompt can be inspected; the cache tests (AC-t12-8) do not.
 */
class RagAclLeakageIT extends AbstractIntegrationTest {

    static final String HAIKU = "claude-haiku-5-5";
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    static final WireMockServer OLLAMA = startWireMock();
    static final String COLLECTION = "handbook";
    static final List<String> RESTRICTED_GROUPS = List.of("security", "finance", "hr", "legal", "sre");
    static final List<AclQuestion> QUESTIONS = AclQuestion.load();

    /** Public documents (group {@code eng}, one also {@code sre}) that most questions half-match. */
    static final Map<String, String[]> PUBLIC_DOCS = publicDocs();

    final String suffix = UUID.randomUUID().toString().substring(0, 8);
    final String tenant = "acl-a-" + suffix;
    final String otherTenant = "acl-b-" + suffix;
    // Obviously fake gateway keys, created for this test only.
    final String engKey = fakeKey("eng");
    final String emptyKey = fakeKey("empty");
    final String adminEmptyKey = fakeKey("admin-empty");
    final String adminEngKey = fakeKey("admin-eng");
    final String otherAdminKey = fakeKey("other-admin");
    final String otherEngKey = fakeKey("other-eng");
    /** For each restricted group, a key with {@code eng} and that group. */
    final Map<String, String> groupKeys = new LinkedHashMap<>();

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    Embedder embedder;

    @Autowired
    Retriever retriever;

    @Autowired
    ChunkSearchRepository repository;

    @Autowired
    GatewayPipeline pipeline;

    @Autowired
    RagProperties ragProperties;

    @Autowired
    DataSource dataSource;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    AclAudit audit;

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    static String fakeKey(String name) {
        return "rgw_test_acl_" + name.replace("-", "_") + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
        registry.add("gateway.providers.ollama.base-url", OLLAMA::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        CLAUDE.stop();
        OLLAMA.stop();
    }

    static Map<String, String[]> publicDocs() {
        Map<String, String[]> docs = new LinkedHashMap<>();
        docs.put("eng.jobs", new String[]{"Jobs",
                "A Job creates Pods and retries them until enough succeed; backoffLimit caps the retries."});
        docs.put("eng.deployments", new String[]{"Deployments",
                "A Deployment rolls out new Pods gradually, and a rollout can be paused to batch changes."});
        docs.put("eng.services", new String[]{"Services",
                "A Service exposes a set of Pods behind one stable virtual address in the cluster."});
        docs.put("eng.probes", new String[]{"Probes",
                "A readiness probe keeps a Pod out of a Service until it can serve traffic."});
        return docs;
    }

    @BeforeEach
    void seed() {
        CLAUDE.resetAll();
        OLLAMA.resetAll();
        audit = new AclAudit(jdbc);
        CLAUDE.stubFor(post("/v1/messages").atPriority(5).willReturn(claudeReply(
                "From the sources [S1][S2][S3][S4][S5][S6][S7][S8].")));

        TestData.insertTenant(jdbc, tenant, 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, tenant, engKey, Set.of("eng"), false);
        TestData.insertKey(jdbc, tenant, emptyKey, Set.of(), false);
        TestData.insertKey(jdbc, tenant, adminEmptyKey, Set.of(), true);
        TestData.insertKey(jdbc, tenant, adminEngKey, Set.of("eng"), true);
        for (String g : RESTRICTED_GROUPS) {
            String k = fakeKey(g);
            groupKeys.put(g, k);
            TestData.insertKey(jdbc, tenant, k, Set.of("eng", g), false);
        }
        TestData.insertTenant(jdbc, otherTenant, 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, otherTenant, otherAdminKey, Set.of(), true);
        TestData.insertKey(jdbc, otherTenant, otherEngKey, Set.of("eng"), false);

        PUBLIC_DOCS.forEach((docId, d) ->
                RagFixtures.document(jdbc, tenant, COLLECTION, docId, d[0], Set.of("eng"), embedder, d[1]));
        RagFixtures.document(jdbc, tenant, COLLECTION, "eng.oncall", "On-call handbook", Set.of("eng", "sre"),
                embedder, "The on-call handbook lists the dashboards to check first during an incident.");
        for (AclQuestion q : QUESTIONS) {
            RagFixtures.document(jdbc, tenant, COLLECTION, q.docId(), q.title(), Set.of(q.group()), embedder,
                    q.content());
        }
    }

    // ---- helpers ----

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder claudeReply(String text) {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(HAIKU, 300, 10, text));
    }

    /** One ask: the response, its body, and the prompt Claude received for it ({@code null} when none was sent). */
    record Asked(HttpResponse<String> response, JsonNode body, String prompt) {
        List<String> shownChunkIds() {
            List<String> ids = new ArrayList<>(body.get("sources").findValuesAsText("chunk_id"));
            ids.addAll(body.get("citations").findValuesAsText("chunk_id"));
            return ids;
        }

        List<String> sourceDocIds() {
            return body.get("sources").findValuesAsText("doc_id");
        }

        /** The prompt up to the question: the source blocks. */
        String promptSources() {
            int q = prompt.lastIndexOf("\n\nQuestion: ");
            assertThat(q).as("prompt shape").isPositive();
            return prompt.substring(0, q);
        }

        String cache() {
            return response.headers().firstValue(GatewayHeaders.CACHE).orElse("");
        }
    }

    Asked ask(String key, String collection, String question, RetrievalMode mode, boolean bypassCache)
            throws Exception {
        CLAUDE.resetRequests();
        String body = JSON.writeValueAsString(Map.of("collection", collection, "question", question,
                "mode", RagService.wire(mode), "top_k", 20));
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + RagController.PATH))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (bypassCache) builder.header(GatewayHeaders.CACHE_BYPASS, "true");
        HttpResponse<String> r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var calls = CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(calls.size()).isLessThanOrEqualTo(1);
        String prompt = calls.isEmpty() ? null
                : JSON.readTree(calls.getFirst().getBodyAsString()).at("/messages/0/content").asText();
        return new Asked(r, JSON.readTree(r.body()), prompt);
    }

    Asked ask(String key, String question, RetrievalMode mode) throws Exception {
        return ask(key, COLLECTION, question, mode, true);
    }

    String chunkIdOf(String tenantId, String docId) {
        return jdbc.sql("SELECT chunk_id::text FROM chunks WHERE tenant_id = ? AND collection_id = ? AND doc_id = ?")
                .params(tenantId, COLLECTION, docId).query(String.class).single();
    }

    Set<String> groupsOfChunk(String chunkId) {
        return Set.copyOf(jdbc.sql("SELECT unnest(acl_groups) FROM chunks WHERE chunk_id = CAST(? AS uuid)")
                .param(chunkId).query(String.class).list());
    }

    /** All four levels for one ask that must not show any of {@code forbidden}. */
    void assertNoLeak(Asked a, String tenantId, Set<String> callerGroups, List<AclQuestion> forbidden) {
        assertThat(audit.leakedChunkIds(tenantId, COLLECTION, callerGroups, a.shownChunkIds()))
                .as("chunk ids shown but not visible").isEmpty();
        for (AclQuestion f : forbidden) {
            assertThat(a.sourceDocIds()).as("sources").doesNotContain(f.docId());
            assertThat(a.body().get("citations").findValuesAsText("doc_id")).as("citations")
                    .doesNotContain(f.docId());
            assertThat(a.response().body()).doesNotContain(f.content()).doesNotContain(f.title());
            if (a.prompt() != null) {
                // The sources part only: the question itself may name the restricted topic.
                assertThat(a.promptSources()).as("upstream prompt").doesNotContain(f.content())
                        .doesNotContain(f.title()).doesNotContain(f.docId());
            }
        }
    }

    // ---- AC-t12-1 ----

    @ParameterizedTest
    @EnumSource(RetrievalMode.class)
    void ac_t12_1_anEngKeySeesOnlyChunksWhoseGroupsIncludeEng(RetrievalMode mode) throws Exception {
        String question = "What does the security vault keep about signing key rotation, regional failover "
                + "and the on-call handbook, and how do Pods roll out?";

        Asked eng = ask(engKey, question, mode);

        assertThat(eng.shownChunkIds()).as("positive: eng sees its own chunks").isNotEmpty();
        assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng"), eng.shownChunkIds())).isEmpty();
        for (String id : eng.shownChunkIds()) assertThat(groupsOfChunk(id)).contains("eng");
        assertNoLeak(eng, tenant, Set.of("eng"), QUESTIONS);
        // A chunk in eng and sre is eng's too.
        if (mode != RetrievalMode.KEYWORD) assertThat(eng.sourceDocIds()).contains("eng.oncall");

        // Positive control: the same question from a key that also holds security and sre retrieves those chunks.
        Asked security = ask(groupKeys.get("security"), question, mode);
        Asked sre = ask(groupKeys.get("sre"), question, mode);
        assertThat(security.sourceDocIds()).contains("security.key-rotation");
        assertThat(security.prompt()).contains(QUESTIONS.getFirst().content());
        assertThat(sre.sourceDocIds()).contains("sre.regional-failover");
        assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng", "security"), security.shownChunkIds()))
                .isEmpty();
        assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng", "sre"), sre.shownChunkIds())).isEmpty();
    }

    // ---- AC-t12-2 ----

    @ParameterizedTest
    @EnumSource(RetrievalMode.class)
    void ac_t12_2_theTenRestrictedQuestionsLeakNothingWithoutTheGroupAndFindTheDocumentWithIt(RetrievalMode mode)
            throws Exception {
        assertThat(QUESTIONS).hasSize(10);
        int leaked = 0;
        int foundWithGroup = 0;
        for (AclQuestion q : QUESTIONS) {
            Asked without = ask(engKey, q.question(), mode);
            leaked += audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng"), without.shownChunkIds()).size();
            assertNoLeak(without, tenant, Set.of("eng"), QUESTIONS);

            Asked with = ask(groupKeys.get(q.group()), q.question(), mode);
            assertThat(with.sourceDocIds()).as(q.id() + " with " + q.group()).contains(q.docId());
            assertThat(with.prompt()).as(q.id()).contains(q.content());
            assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng", q.group()), with.shownChunkIds()))
                    .isEmpty();
            // The key with the group still sees no other restricted group's documents.
            assertNoLeak(with, tenant, Set.of("eng", q.group()),
                    QUESTIONS.stream().filter(o -> !o.group().equals(q.group())).toList());
            foundWithGroup++;
        }
        assertThat(leaked).isZero();
        assertThat(foundWithGroup).isEqualTo(10);
    }

    @Test
    void ac_t12_2_citationsOfTheRestrictedDocumentAppearOnlyForTheKeyWithTheGroup() throws Exception {
        AclQuestion q = QUESTIONS.getFirst();
        CLAUDE.stubFor(post("/v1/messages").atPriority(1).withRequestBody(containing("Signing key rotation"))
                .willReturn(claudeReply("Every 90 days [S1][S2][S3][S4][S5][S6][S7][S8].")));

        Asked with = ask(groupKeys.get(q.group()), q.question(), RetrievalMode.HYBRID);
        Asked without = ask(engKey, q.question(), RetrievalMode.HYBRID);

        assertThat(with.body().get("citations").findValuesAsText("doc_id")).contains(q.docId());
        assertThat(without.body().get("citations")).isNotEmpty();
        assertThat(without.body().get("citations").findValuesAsText("doc_id")).doesNotContain(q.docId());
        assertThat(without.body().get("answer").asText()).doesNotContain("90 days");
    }

    // ---- AC-t12-3 ----

    @ParameterizedTest
    @EnumSource(RetrievalMode.class)
    void ac_t12_3_anotherTenantsKeyWithTheSameGroupNameGetsNoneOfThisTenantsChunks(RetrievalMode mode)
            throws Exception {
        // Tenant B reuses a doc id, the collection and the group name; its text is marked.
        RagFixtures.document(jdbc, otherTenant, COLLECTION, "eng.jobs", "Jobs", Set.of("eng"), embedder,
                "OTHERTENANT: a Job in the other tenant retries Pods with its own backoffLimit.");
        String question = "How does a Job retry Pods with backoffLimit, and how often does the signing key rotate?";
        List<String> tenantATexts = new ArrayList<>(PUBLIC_DOCS.values().stream().map(d -> d[1]).toList());
        QUESTIONS.forEach(q -> tenantATexts.add(q.content()));

        Asked b = ask(otherEngKey, question, mode);

        assertThat(b.sourceDocIds()).as("positive: tenant B sees its own chunk").containsExactly("eng.jobs");
        assertThat(b.shownChunkIds()).contains(chunkIdOf(otherTenant, "eng.jobs"))
                .doesNotContain(chunkIdOf(tenant, "eng.jobs"));
        assertThat(audit.leakedChunkIds(otherTenant, COLLECTION, Set.of("eng"), b.shownChunkIds())).isEmpty();
        assertThat(b.prompt()).contains("OTHERTENANT");
        for (String text : tenantATexts) assertThat(b.prompt()).doesNotContain(text);

        // Positive control in tenant A: the same question with the same group sees A's chunk and not B's.
        Asked a = ask(engKey, question, mode);
        assertThat(a.shownChunkIds()).contains(chunkIdOf(tenant, "eng.jobs"))
                .doesNotContain(chunkIdOf(otherTenant, "eng.jobs"));
        assertThat(a.prompt()).doesNotContain("OTHERTENANT");
    }

    @Test
    void ac_t12_3_bothLegsKeepToTheTenantEvenForTheOtherTenantsBestMatch() {
        String question = "signing key rotation every 90 days";
        // Tenant A's chunk at vector distance 0 and with the question's words repeated, in group eng.
        UUID doc = RagFixtures.document(jdbc, tenant, COLLECTION, "eng.best", "Best", Set.of("eng"), embedder);
        String best = RagFixtures.chunk(jdbc, doc, tenant, COLLECTION, "eng.best", "Best", 0,
                "signing key rotation every 90 days signing key rotation", new String[]{"eng"},
                embedder.embedQuery(question)).toString();
        RagFixtures.document(jdbc, otherTenant, COLLECTION, "eng.own", "Own", Set.of("eng"), embedder,
                "Tenant B rotates its signing key on its own schedule.");

        var keyword = repository.keyword(otherTenant, COLLECTION, Set.of("eng"), question, 50);
        var vector = repository.vector(otherTenant, COLLECTION, Set.of("eng"), embedder.embedQuery(question), 50);

        assertThat(keyword).extracting(ChunkSearchRepository.Row::chunkId).doesNotContain(best).hasSize(1);
        assertThat(vector).extracting(ChunkSearchRepository.Row::chunkId).doesNotContain(best).hasSize(1);
        assertThat(keyword.getFirst().docId()).isEqualTo("eng.own");
        assertThat(vector.getFirst().docId()).isEqualTo("eng.own");
        // Positive control: tenant A ranks the planted chunk first in both legs.
        assertThat(repository.keyword(tenant, COLLECTION, Set.of("eng"), question, 50).getFirst().chunkId())
                .isEqualTo(best);
        assertThat(repository.vector(tenant, COLLECTION, Set.of("eng"), embedder.embedQuery(question), 50)
                .getFirst().chunkId()).isEqualTo(best);
    }

    @Test
    void anotherCollectionOfTheSameTenantAndGroupIsNotSearched() throws Exception {
        RagFixtures.document(jdbc, tenant, "other-collection", "eng.jobs", "Jobs", Set.of("eng"), embedder,
                "OTHERCOLLECTION: a Job retries Pods until backoffLimit.");

        Asked inHandbook = ask(engKey, COLLECTION, "How does a Job retry Pods?", RetrievalMode.HYBRID, true);
        Asked inOther = ask(engKey, "other-collection", "How does a Job retry Pods?", RetrievalMode.HYBRID, true);

        assertThat(inHandbook.prompt()).doesNotContain("OTHERCOLLECTION");
        assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng"), inHandbook.shownChunkIds())).isEmpty();
        assertThat(inOther.prompt()).contains("OTHERCOLLECTION");   // positive control
        assertThat(inOther.sourceDocIds()).containsExactly("eng.jobs");
    }

    // ---- admin keys retrieve with their own groups (D-49, D-51) ----

    @Test
    void anAdminKeyRetrievesWithItsOwnGroupsAndGetsNoBypass() throws Exception {
        AclQuestion q = QUESTIONS.getFirst();

        Asked adminNoGroups = ask(adminEmptyKey, q.question(), RetrievalMode.HYBRID);
        Asked adminEng = ask(adminEngKey, q.question(), RetrievalMode.HYBRID);

        assertThat(adminNoGroups.body().get("no_answer").asBoolean()).isTrue();
        assertThat(adminNoGroups.prompt()).isNull();
        assertNoLeak(adminEng, tenant, Set.of("eng"), QUESTIONS);
        assertThat(adminEng.shownChunkIds()).as("positive: the admin's eng group works").isNotEmpty();
        assertThat(ask(groupKeys.get(q.group()), q.question(), RetrievalMode.HYBRID).sourceDocIds())
                .contains(q.docId());
    }

    // ---- AC-t12-4 ----

    @Test
    void ac_t12_4_anEmptyGroupKeyGetsNoAnswerOverHttpWithNoProviderOrEmbedderCall() throws Exception {
        Asked empty = ask(emptyKey, QUESTIONS.getFirst().question(), RetrievalMode.HYBRID);

        assertThat(empty.body().get("no_answer").asBoolean()).isTrue();
        assertThat(empty.body().get("answer").asText()).isEqualTo(RagAnswer.NO_ANSWER_TEXT);
        assertThat(empty.body().get("sources")).isEmpty();
        assertThat(empty.prompt()).isNull();
        assertThat(CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
        assertThat(OLLAMA.getAllServeEvents()).isEmpty();
        // Positive control: a key with a group gets an answer from the same collection.
        assertThat(ask(engKey, QUESTIONS.getFirst().question(), RetrievalMode.HYBRID).prompt()).isNotNull();
    }

    /**
     * The retrieval stack and {@link RagService} on a DataSource that counts connections and statements, with the
     * app's own pipeline (so a provider call would reach WireMock): empty groups make none of either.
     */
    @Test
    void ac_t12_4_anEmptyGroupSetRunsNoSqlStatementAndNoProviderCall() {
        var counting = new CountingDataSource(dataSource);
        var meters = new SimpleMeterRegistry();
        var repo = new ChunkSearchRepository(JdbcClient.create(counting.proxy()),
                new DataSourceTransactionManager(counting.proxy()), ragProperties, meters);
        var embeds = new AtomicInteger();
        Embedder countingEmbedder = new Embedder() {
            public int dimensions() { return embedder.dimensions(); }
            public List<float[]> embedDocuments(List<String> texts) { return embedder.embedDocuments(texts); }
            public float[] embedQuery(String text) { embeds.incrementAndGet(); return embedder.embedQuery(text); }
        };
        try (var hybrid = new HybridRetriever(repo, countingEmbedder, ragProperties, meters)) {
            var service = new RagService(hybrid, pipeline, ragProperties, meters);
            var tenantRow = new Tenant(tenant, "Tenant", 1_000_000, BigDecimal.TEN, Set.of());

            for (RetrievalMode mode : RetrievalMode.values()) {
                RagService.Result r = service.answer(new ApiKeyPrincipal("k-empty", tenantRow, Set.of(), false),
                        question(mode));
                assertThat(r.body().noAnswer()).isTrue();
            }
            // The repository on its own refuses empty groups without SQL too (and counts no exact fallback).
            assertThat(repo.keyword(tenant, COLLECTION, Set.of(), "anything", 50)).isEmpty();
            assertThat(repo.vector(tenant, COLLECTION, Set.of(), embedder.embedQuery("anything"), 50)).isEmpty();

            assertThat(counting.connections.get()).isZero();
            assertThat(counting.statements.get()).isZero();
            assertThat(embeds.get()).isZero();
            assertThat(CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
            assertThat(meters.counter(MetricNames.RETRIEVAL_EXACT_FALLBACK).count()).isZero();

            // Positive control: the same stack with a group runs SQL, embeds and calls the provider once.
            RagService.Result answered = service.answer(
                    new ApiKeyPrincipal("k-eng", tenantRow, Set.of("eng"), false), question(RetrievalMode.HYBRID));
            assertThat(answered.body().noAnswer()).isFalse();
            assertThat(counting.statements.get()).isPositive();
            assertThat(embeds.get()).isEqualTo(1);
            assertThat(CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).hasSize(1);
        }
    }

    static RagService.Question question(RetrievalMode mode) {
        return new RagService.Question(COLLECTION, "How does a Job retry Pods?", 5, mode,
                UUID.randomUUID().toString(), System.nanoTime(), true);
    }

    /** Counts {@code getConnection} calls and statements created on them. */
    static final class CountingDataSource {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger statements = new AtomicInteger();
        private final DataSource target;

        CountingDataSource(DataSource target) {
            this.target = target;
        }

        DataSource proxy() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (p, m, args) -> {
                        Object result = call(target, m, args);
                        if (m.getName().equals("getConnection")) {
                            connections.incrementAndGet();
                            return connection((Connection) result);
                        }
                        return result;
                    });
        }

        private Connection connection(Connection c) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, args) -> {
                        if (Set.of("prepareStatement", "createStatement", "prepareCall").contains(m.getName())) {
                            statements.incrementAndGet();
                        }
                        return call(c, m, args);
                    });
        }

        private static Object call(Object target, Method m, Object[] args) throws Throwable {
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    // ---- AC-t12-8: the cache ----

    void stubSecurityAwareClaude() {
        CLAUDE.stubFor(post("/v1/messages").atPriority(1).withRequestBody(containing("VAULTMARKER"))
                .willReturn(claudeReply("Use the VAULTMARKER ceremony [S1].")));
        CLAUDE.stubFor(post("/v1/messages").atPriority(2)
                .willReturn(claudeReply("Use the token rotation job [S1].")));
    }

    void seedTokenDocs() {
        RagFixtures.document(jdbc, tenant, COLLECTION, "security.vault-token", "Vault root token", Set.of("security"),
                embedder, "Rotate the vault root token with the VAULTMARKER ceremony and two key holders.");
        RagFixtures.document(jdbc, tenant, COLLECTION, "eng.token-rotation", "Token rotation", Set.of("eng"),
                embedder, "Rotate the application token with the token rotation job.");
    }

    static final String TOKEN_QUESTION = "How do we rotate the token?";

    @Test
    void ac_t12_8_aSecurityKeysCachedAnswerIsNeverServedToAnEngKey() throws Exception {
        stubSecurityAwareClaude();
        seedTokenDocs();
        String securityOnly = fakeKey("security-only");
        TestData.insertKey(jdbc, tenant, securityOnly, Set.of("security"), false);

        Asked a = ask(securityOnly, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);
        Asked b = ask(engKey, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);
        Asked aAgain = ask(securityOnly, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);
        Asked bAgain = ask(engKey, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);

        // Positive control: A saw the security chunk, and A's second ask was a cache hit.
        assertThat(a.cache()).isEqualTo("MISS");
        assertThat(a.body().get("answer").asText()).contains("VAULTMARKER");
        assertThat(a.sourceDocIds()).contains("security.vault-token");
        assertThat(aAgain.cache()).isEqualTo("HIT");
        assertThat(aAgain.body().get("answer").asText()).contains("VAULTMARKER");
        // B asked after A: a miss with its own prompt, then its own hit; never A's text.
        assertThat(b.cache()).isEqualTo("MISS");
        assertThat(b.prompt()).isNotNull().doesNotContain("VAULTMARKER");
        assertThat(bAgain.cache()).isEqualTo("HIT");
        for (Asked x : List.of(b, bAgain)) {
            assertThat(x.response().body()).doesNotContain("VAULTMARKER").doesNotContain("security.vault-token")
                    .doesNotContain("Vault root token");
            assertThat(x.sourceDocIds()).contains("eng.token-rotation");
            assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng"), x.shownChunkIds())).isEmpty();
        }
    }

    @Test
    void ac_t12_8_theOrderDoesNotMatterAnEngKeyAskingFirstThenAfterTheSecurityKeyStillSeesNoSecurityText()
            throws Exception {
        stubSecurityAwareClaude();
        seedTokenDocs();
        String securityOnly = fakeKey("security-only");
        TestData.insertKey(jdbc, tenant, securityOnly, Set.of("security"), false);

        Asked b = ask(engKey, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);
        Asked a = ask(securityOnly, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);
        Asked bAgain = ask(engKey, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);

        assertThat(a.cache()).as("A is not served B's entry either").isEqualTo("MISS");
        assertThat(a.body().get("answer").asText()).contains("VAULTMARKER");
        assertThat(bAgain.cache()).isEqualTo("HIT");
        for (Asked x : List.of(b, bAgain)) {
            assertThat(x.response().body()).doesNotContain("VAULTMARKER");
            assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng"), x.shownChunkIds())).isEmpty();
        }
    }

    /**
     * D-51 "Cache and ACL changes": once a chunk's groups no longer include the caller's, the caller retrieves
     * different chunks, builds a different prompt and misses the cached answer. The groups are changed here with SQL,
     * as t11's {@code acl_updated} path does inside its PUT transaction; AC-t12-7 repeats this through the PUT.
     */
    @Test
    void aCachedAnswerDoesNotOutliveTheRemovalOfTheCallersGroupFromTheChunk() throws Exception {
        stubSecurityAwareClaude();
        seedTokenDocs();
        String securityAndEng = groupKeys.get("security");

        Asked before = ask(securityAndEng, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);
        Asked cached = ask(securityAndEng, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);
        jdbc.sql("UPDATE chunks SET acl_groups = '{legal}' WHERE tenant_id = ? AND doc_id = 'security.vault-token'")
                .param(tenant).update();
        Asked after = ask(securityAndEng, COLLECTION, TOKEN_QUESTION, RetrievalMode.KEYWORD, false);

        assertThat(before.body().get("answer").asText()).contains("VAULTMARKER");
        assertThat(cached.cache()).as("positive: the answer was cached").isEqualTo("HIT");
        assertThat(after.cache()).isEqualTo("MISS");
        assertThat(after.response().body()).doesNotContain("VAULTMARKER");
        assertThat(after.prompt()).doesNotContain("VAULTMARKER");
        assertThat(audit.leakedChunkIds(tenant, COLLECTION, Set.of("eng", "security"), after.shownChunkIds()))
                .isEmpty();
    }

    // ---- key group changes: within the key cache TTL (D-23, D-51) ----

    @Test
    void aKeyGroupChangeAppliesOnceTheKeyCacheTtlHasPassed() {
        String key = fakeKey("changing");
        UUID keyId = TestData.insertKey(jdbc, tenant, key, Set.of("security"), false);
        var nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        var authenticator = new JdbcApiKeyAuthenticator(jdbc, JdbcApiKeyAuthenticator.DEFAULT_TTL, 100, ticker);
        AclQuestion q = QUESTIONS.getFirst();

        ApiKeyPrincipal first = authenticator.authenticate(key).orElseThrow();
        assertThat(docIds(first, q)).as("positive: security retrieves its document").contains(q.docId());

        jdbc.sql("UPDATE api_keys SET groups = '{eng}' WHERE id = ?").param(keyId).update();
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        ApiKeyPrincipal withinTtl = authenticator.authenticate(key).orElseThrow();
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        ApiKeyPrincipal afterTtl = authenticator.authenticate(key).orElseThrow();

        assertThat(withinTtl.groups()).as("accepted: up to 30 s per instance").containsExactly("security");
        assertThat(afterTtl.groups()).containsExactly("eng");
        assertThat(docIds(afterTtl, q)).doesNotContain(q.docId());
    }

    List<String> docIds(ApiKeyPrincipal caller, AclQuestion q) {
        return retriever.retrieve(new RetrievalQuery(caller.tenant().id(), COLLECTION, q.question(), caller.groups(),
                20, RetrievalMode.HYBRID)).chunks().stream().map(RetrievedChunk::docId).toList();
    }

    // ---- AC-t12-9: the document GET ----

    HttpResponse<String> getDocument(String key, String docId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        baseUrl() + "/v1/rag/collections/" + COLLECTION + "/documents/" + docId))
                .header("Authorization", "Bearer " + key).timeout(Duration.ofSeconds(30)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ac_t12_9_theDocumentGetIs404AcrossTenants403ForNonAdminsAndNeverReturnsContent() throws Exception {
        AclQuestion q = QUESTIONS.getFirst();

        HttpResponse<String> own = getDocument(adminEmptyKey, q.docId());
        HttpResponse<String> otherAdmin = getDocument(otherAdminKey, q.docId());
        HttpResponse<String> missing = getDocument(otherAdminKey, "no.such.document");
        HttpResponse<String> nonAdmin = getDocument(groupKeys.get(q.group()), q.docId());
        HttpResponse<String> otherNonAdmin = getDocument(otherEngKey, q.docId());

        // Positive control: the tenant's own admin sees the metadata.
        assertThat(own.statusCode()).isEqualTo(200);
        JsonNode meta = JSON.readTree(own.body());
        assertThat(meta.get("doc_id").asText()).isEqualTo(q.docId());
        assertThat(meta.get("acl_groups")).hasSize(1);
        assertThat(meta.has("content")).isFalse();
        assertThat(otherAdmin.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(otherAdmin.body()).at("/error/code").asText()).isEqualTo("not_found");
        assertThat(JSON.readTree(otherAdmin.body()).at("/error/message"))
                .isEqualTo(JSON.readTree(missing.body()).at("/error/message"));
        // Even holding the document's group does not make a non-admin key a reader of document metadata.
        assertThat(nonAdmin.statusCode()).isEqualTo(403);
        assertThat(otherNonAdmin.statusCode()).isEqualTo(403);
        for (HttpResponse<String> r : List.of(own, otherAdmin, missing, nonAdmin, otherNonAdmin)) {
            assertThat(r.body()).doesNotContain(q.content());
        }
        for (HttpResponse<String> r : List.of(otherAdmin, missing, nonAdmin, otherNonAdmin)) {
            assertThat(r.body()).doesNotContain(q.title()).doesNotContain("security");
        }
    }

    /**
     * "No endpoint returns a document's content": the RAG API's only GET is the metadata GET above, and a RAG answer
     * names its sources by id and title without their text.
     */
    @Test
    void ac_t12_9_noRagEndpointReturnsADocumentsContent() throws Exception {
        Set<String> ragGets = new HashSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            boolean get = info.getMethodsCondition().getMethods().isEmpty()
                    || info.getMethodsCondition().getMethods().contains(RequestMethod.GET);
            info.getPatternValues().stream().filter(p -> p.startsWith("/v1/rag")).filter(p -> get)
                    .forEach(ragGets::add);
        });
        assertThat(ragGets).containsExactly("/v1/rag/collections/{collection}/documents/{docId}");

        AclQuestion q = QUESTIONS.getFirst();
        Asked answered = ask(groupKeys.get(q.group()), q.question(), RetrievalMode.HYBRID);
        assertThat(answered.prompt()).as("positive: the text did reach the model").contains(q.content());
        assertThat(answered.response().body()).doesNotContain(q.content());
        for (JsonNode s : answered.body().get("sources")) {
            assertThat(s.has("text")).isFalse();
            assertThat(s.has("content")).isFalse();
        }
    }

    // ---- the questions file ----

    /** One line of {@code rag/acl-questions.jsonl}: a question whose only relevant document is restricted. */
    record AclQuestion(String id, String question, String group, String docId, String title, String content) {

        static List<AclQuestion> load() {
            try (InputStream in = RagAclLeakageIT.class.getResourceAsStream("/rag/acl-questions.jsonl")) {
                if (in == null) throw new IllegalStateException("rag/acl-questions.jsonl is missing");
                List<AclQuestion> out = new ArrayList<>();
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    if (line.isBlank()) continue;
                    JsonNode n = JSON.readTree(line);
                    out.add(new AclQuestion(n.get("id").asText(), n.get("question").asText(), n.get("group").asText(),
                            n.get("doc_id").asText(), n.get("title").asText(), n.get("content").asText()));
                }
                return List.copyOf(out);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void theQuestionsFileHasTenRestrictedQuestionsEachWithARestrictedGroup() {
        assertThat(QUESTIONS).hasSize(10);
        assertThat(QUESTIONS).extracting(AclQuestion::id).doesNotHaveDuplicates();
        assertThat(QUESTIONS).extracting(AclQuestion::docId).doesNotHaveDuplicates();
        assertThat(QUESTIONS).allSatisfy(q -> assertThat(RESTRICTED_GROUPS).contains(q.group()));
    }
}
