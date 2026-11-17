package dev.raggateway.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.eval.judge.AnswerEvalRunner;
import dev.raggateway.eval.judge.AnswerScorer;
import dev.raggateway.eval.judge.JudgeClient;
import dev.raggateway.eval.judge.JudgeSettings;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.rag.ingest.IndexingWorker;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.SharedContainers;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer eval end to end (t13, B's final commit): {@code answers collect} asks the real {@code POST /v1/rag/query}
 * (real {@code RagService}, retriever and pipeline; fake embedder under the test profile) every question of the
 * committed {@code eval/questions.v1.jsonl}, then {@code answers score --dry-run} estimates the judge run on the
 * collected file. Both go through {@link EvalMain}.
 *
 * <ul>
 *   <li>The corpus is the gold evidence passages themselves, one document per gold document id, so every answerable
 *       question can retrieve its passage without the real corpus (not fetched in tests).</li>
 *   <li>WireMock plays the generator (the gateway's Claude provider). It follows the RAG system prompt: "I don't
 *       know." for the unanswerable questions, a cited answer otherwise.</li>
 *   <li>A second WireMock stands where the judge API would be; it has no stubs and must record no request, and the
 *       judge client factory must never be called. Nothing reaches Anthropic: there is no key and every base URL is
 *       WireMock's.</li>
 *   <li>The collected file lives in a temporary directory, never under {@code eval/results/}.</li>
 * </ul>
 */
class AnswersEndToEndIT extends AbstractIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer GENERATOR = start();
    static final WireMockServer JUDGE = start();
    static final String COLLECTION = "k8s-c400";

    final String tenant = "eval-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    // Obviously fake gateway keys created for this test.
    final String admin = "rgw_test_eval_e2e_admin_" + UUID.randomUUID().toString().replace("-", "");
    final String reader = "rgw_test_eval_e2e_reader_" + UUID.randomUUID().toString().replace("-", "");
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    IndexingWorker worker;

    static WireMockServer start() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", GENERATOR::baseUrl);
        registry.add("gateway.providers.ollama.base-url", GENERATOR::baseUrl);
    }

    @AfterAll
    static void stop() {
        GENERATOR.stop();
        JUDGE.stop();
    }

    @Test
    void collectsAnswersThroughTheRealEndpointAndDryRunsTheJudgeWithinTheCapWithoutANetworkCall(@TempDir Path dir)
            throws Exception {
        TestData.insertTenant(jdbc, tenant, 10_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, tenant, admin, Set.of(), true);
        TestData.insertKey(jdbc, tenant, reader, Set.of("eng", "finance"), false);

        List<JsonNode> questions = new ArrayList<>();
        for (String line : Files.readAllLines(EvalMain.QUESTIONS, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) questions.add(JSON.readTree(line));
        }
        Map<String, List<String>> passages = new LinkedHashMap<>();
        for (JsonNode q : questions) {
            for (JsonNode g : q.path("gold")) {
                passages.computeIfAbsent(g.path("doc_id").asText(), k -> new ArrayList<>()).add(g.path("evidence").asText());
            }
        }
        for (var e : passages.entrySet()) put(e.getKey(), String.join("\n\n", e.getValue()));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() -> {
            worker.pollOnce();
            return jdbc.sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND status = 'indexed'")
                    .param(tenant).query(Long.class).single() == passages.size();
        });

        // The generator: a cited answer by default, "I don't know." for each unanswerable question.
        GENERATOR.stubFor(post("/v1/messages").atPriority(5).willReturn(sse(
                ClaudeSse.reply("claude-haiku-5-5", 300, 8, "The sources say so ", "[S1]."))));
        int unanswerable = 0;
        for (JsonNode q : questions) {
            if (!"unanswerable".equals(q.path("kind").asText())) continue;
            unanswerable++;
            String marker = q.path("question").asText().substring(0, 25);
            assertThat(marker).doesNotContain("\"", "\\");
            GENERATOR.stubFor(post("/v1/messages").atPriority(1).withRequestBody(containing(marker))
                    .willReturn(sse(ClaudeSse.reply("claude-haiku-5-5", 300, 4, "I don't know."))));
        }
        assertThat(unanswerable).isEqualTo(5);

        AtomicInteger clients = new AtomicInteger();
        AnswerEvalRunner.ClientFactory factory = (settings, baseUrl, env) -> {
            clients.incrementAndGet();
            return JudgeClient.create(settings, baseUrl, env);
        };
        var pg = SharedContainers.postgres();
        Map<String, String> env = Map.of(
                AnswerEvalRunner.GATEWAY_KEY_ENV, reader,
                "DB_URL", pg.getJdbcUrl(), "POSTGRES_USER", pg.getUsername(), "POSTGRES_PASSWORD", pg.getPassword(),
                // present on purpose: neither step may build a judge client without --confirm-spend
                JudgeSettings.API_KEY_ENV, "test-judge-key-not-real");
        Path answers = dir.resolve("answers.jsonl");

        Run collect = run(env, factory, "answers", "collect", "--questions", EvalMain.QUESTIONS.toString(),
                "--out", answers.toString(), "--gateway", baseUrl(), "--tenant", tenant, "--collection", COLLECTION);
        assertThat(collect.code()).as(collect.out()).isZero();

        long asked = questions.stream().filter(q -> !"acl".equals(q.path("kind").asText())).count();
        assertThat(collect.out()).contains("collected " + asked + " answers");
        List<AnswerScorer.Item> items = AnswerEvalRunner.readItems(answers);
        assertThat(items).hasSize((int) asked);
        // AC-t13-8 on the real endpoint: citations within sources everywhere, 5 of 5 unanswerable refused
        assertThat(items).allMatch(i -> DeterministicChecks.citationsWithinSources(i.response()));
        assertThat(items.stream().filter(AnswerScorer.Item::unanswerable)
                .filter(i -> DeterministicChecks.isRefusal(i.response())).count()).isEqualTo(5);
        for (AnswerScorer.Item i : items.stream().filter(AnswerScorer.Item::answerable).toList()) {
            assertThat(i.response().noAnswer()).as(i.id()).isFalse();
            assertThat(i.response().retrievalMode()).as(i.id()).isEqualTo("hybrid");
            assertThat(i.response().citations()).as(i.id()).isNotEmpty();
            // the cited source's text was read back from the chunk table for the judge
            assertThat(i.sourceTexts()).as(i.id()).containsKey(i.response().citations().get(0).source());
        }

        Run dry = run(env, factory, "answers", "score", "--answers", answers.toString(), "--dry-run",
                "--judge-base-url", JUDGE.baseUrl());
        assertThat(dry.code()).as(dry.out()).isZero();
        long answerable = items.stream().filter(AnswerScorer.Item::answerable).count();
        assertThat(dry.out()).contains(answerable + " judge prompts");
        Matcher m = Pattern.compile("estimated worst-case cost \\$([0-9.]+) .*cap \\$([0-9.]+)").matcher(dry.out());
        assertThat(m.find()).as(dry.out()).isTrue();
        assertThat(new BigDecimal(m.group(1))).isPositive().isLessThanOrEqualTo(new BigDecimal(m.group(2)));
        assertThat(dry.out()).contains("all " + answerable + " calls fit under the cap");

        // no judge client, no request where the judge API would be; the generator saw only the gateway's calls
        assertThat(clients.get()).isZero();
        assertThat(JUDGE.getAllServeEvents()).isEmpty();
        assertThat(GENERATOR.findAll(postRequestedFor(urlEqualTo("/v1/messages"))))
                .hasSize((int) items.stream().filter(i -> !i.response().noAnswer()).count());
        assertThat(Files.exists(Path.of("eval/results").resolve(answers.getFileName()))).isFalse();
    }

    record Run(int code, String out) {
    }

    static Run run(Map<String, String> env, AnswerEvalRunner.ClientFactory factory, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = EvalMain.answers(args, env, new PrintStream(bytes, true, StandardCharsets.UTF_8), factory);
        return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sse(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(body);
    }

    void put(String docId, String content) throws Exception {
        String body = JSON.writeValueAsString(Map.of("title", docId, "content", content, "acl_groups", List.of("eng")));
        var request = HttpRequest.newBuilder(URI.create(
                        baseUrl() + "/v1/rag/collections/" + COLLECTION + "/documents/" + docId))
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
    }
}
