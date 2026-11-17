package dev.raggateway.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.raggateway.eval.DeterministicChecks;
import dev.raggateway.rag.query.RagAnswer;
import dev.raggateway.validation.NetworkntOutputValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.raggateway.eval.judge.JudgeStubs.FAKE_KEY;
import static dev.raggateway.eval.judge.JudgeStubs.JSON;
import static dev.raggateway.eval.judge.JudgeStubs.ok;
import static dev.raggateway.eval.judge.JudgeStubs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer-eval command line against WireMock only: WireMock is the judge API in every test, and also the
 * gateway in the collect test. No real API is reachable from here and no key exists; keys are fake strings.
 */
class AnswerEvalRunnerTest {

    static WireMockServer wireMock;
    static final String GATEWAY_KEY = "test-gateway-eval-key-not-real";

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    /** Counts factory calls; builds a real client (pointed at WireMock) only when asked. */
    static final class CountingFactory implements AnswerEvalRunner.ClientFactory {
        final AtomicInteger created = new AtomicInteger();

        @Override
        public JudgeClient create(JudgeSettings settings, String baseUrl, java.util.function.Function<String, String> env) {
            created.incrementAndGet();
            return JudgeClient.create(settings, baseUrl, env);
        }
    }

    record Run(int code, String out) {
    }

    static Run run(Map<String, String> env, AnswerEvalRunner.ClientFactory factory, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = AnswerEvalRunner.run(args, env, new PrintStream(bytes, true, StandardCharsets.UTF_8), factory);
        return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------- sample data (test data, not the eval set)

    static final String CHUNK = ("A Deployment provides declarative updates for Pods and ReplicaSets. You describe a"
            + " desired state in a Deployment, and the Deployment Controller changes the actual state to the desired"
            + " state at a controlled rate. ").repeat(8); // ~1,500 characters, roughly a 400-token chunk

    /** One collected answer line as {@code collect} writes it. */
    static ObjectNode line(String id, String kind, String mode, boolean refuse) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", id);
        n.put("kind", kind);
        n.put("question", "How does a Deployment roll out a new version of an application? (" + id + ")");
        ArrayNode gold = n.putArray("gold");
        if (!"unanswerable".equals(kind)) {
            gold.addObject().put("doc_id", "concepts.workloads.controllers.deployment")
                    .put("evidence", "the Deployment Controller changes the actual state to the desired state");
        }
        ObjectNode r = n.putObject("response");
        r.put("answer", refuse ? "I don't know." : "It replaces Pods at a controlled rate [S1][S2].");
        ArrayNode citations = r.putArray("citations");
        ArrayNode sources = r.putArray("sources");
        ObjectNode texts = n.putObject("source_texts");
        for (int i = 1; i <= 5; i++) {
            String chunk = "00000000-0000-0000-0000-00000000000" + i;
            sources.addObject().put("source", "S" + i).put("chunk_id", chunk).put("doc_id", "doc." + i)
                    .put("title", "Deployments").put("score", 0.03).put("keyword_rank", i).put("vector_rank", i);
            if (!refuse && i <= 2) {
                citations.addObject().put("source", "S" + i).put("chunk_id", chunk).put("doc_id", "doc." + i)
                        .put("title", "Deployments");
            }
            texts.put("S" + i, CHUNK);
        }
        r.put("no_answer", false);
        r.put("retrieval_mode", mode);
        r.put("model", "claude-test");
        r.putNull("usage");
        r.put("request_id", "req-" + id);
        return n;
    }

    /** 15 conceptual, 8 exact-term and 5 unanswerable answers (the eval set's minimum shape, minus acl). */
    static Path sample(Path dir, String mode) throws Exception {
        List<String> lines = new ArrayList<>();
        int q = 1;
        for (int i = 0; i < 15; i++) lines.add(line("q%02d".formatted(q++), "conceptual", mode, false).toString());
        for (int i = 0; i < 8; i++) lines.add(line("q%02d".formatted(q++), "exact_term", mode, false).toString());
        for (int i = 0; i < 5; i++) lines.add(line("q%02d".formatted(q++), "unanswerable", mode, true).toString());
        Path f = dir.resolve("answers.jsonl");
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    // ------------------------------------------------------------- tests

    @Test
    void ac_t13_12_dryRunBuildsEveryPromptMakesNoNetworkCallAndPrintsACostWithinTheCap(@TempDir Path dir)
            throws Exception {
        Path answers = sample(dir, "hybrid");
        CountingFactory factory = new CountingFactory();
        // a key is present on purpose: the dry run must still never build a client
        Run r = run(Map.of(JudgeSettings.API_KEY_ENV, FAKE_KEY), factory, "answers", "score", "--answers",
                answers.toString(), "--dry-run", "--judge-base-url", wireMock.baseUrl());

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(factory.created.get()).isZero();
        assertThat(wireMock.getAllServeEvents()).isEmpty(); // a WireMock with no stubs recorded 0 requests
        assertThat(r.out()).contains("23 judge prompts").contains("claude-haiku-5-5");
        Matcher m = Pattern.compile("estimated worst-case cost \\$([0-9.]+) .*cap \\$([0-9.]+)").matcher(r.out());
        assertThat(m.find()).as(r.out()).isTrue();
        BigDecimal cost = new BigDecimal(m.group(1));
        assertThat(cost).isPositive().isLessThanOrEqualTo(new BigDecimal(m.group(2)));
        assertThat(new BigDecimal(m.group(2))).isEqualByComparingTo("0.50");
        assertThat(r.out()).contains("all 23 calls fit under the cap");
    }

    @Test
    void ac_t13_12_withoutConfirmSpendTheRealClientIsNeverBuilt(@TempDir Path dir) throws Exception {
        Path answers = sample(dir, "hybrid");
        CountingFactory factory = new CountingFactory();
        Map<String, String> env = Map.of(JudgeSettings.API_KEY_ENV, FAKE_KEY);

        Run neither = run(env, factory, "score", "--answers", answers.toString(), "--judge-base-url", wireMock.baseUrl());
        Run both = run(env, factory, "score", "--answers", answers.toString(), "--dry-run", "--confirm-spend",
                "--judge-base-url", wireMock.baseUrl());

        assertThat(neither.code()).isEqualTo(2);
        assertThat(neither.out()).contains("--confirm-spend");
        assertThat(both.code()).isEqualTo(2);
        assertThat(factory.created.get()).isZero();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void confirmSpendScoresUnderTheCapAndWritesASchemaValidResultsFile(@TempDir Path dir) throws Exception {
        wireMock.stubFor(post("/v1/messages").willReturn(ok(JudgeStubs.thinkingThenText(verdict(2, true), 900, 250))));
        Path answers = sample(dir, "keyword");
        Path results = dir.resolve("20000101T0000Z-test-data.json");
        Files.writeString(results, """
                {"run_id":"20000101T0000Z-test-data","measured_on":"test data, not a measurement","date":"2000-01-01",
                 "git_commit":"0000000",
                 "corpus":{"repo":"kubernetes/website","commit":"adaa04d017c75981660828f869b364a266b0ef53","documents":243,
                           "eval_set":"questions.v1","questions":38},
                 "embedder":{"kind":"none"},
                 "retrieval":[{"mode":"keyword","chunk_target_tokens":400,"questions":23,"recall_at_1":0,"recall_at_5":0,
                               "recall_at_10":0,"mrr":0,"ndcg_at_10":0}]}
                """);
        CountingFactory factory = new CountingFactory();

        Run r = run(Map.of(JudgeSettings.API_KEY_ENV, FAKE_KEY), factory, "score", "--answers", answers.toString(),
                "--confirm-spend", "--cap-usd", "0.20", "--mode", "keyword", "--into", results.toString(),
                "--judge-base-url", wireMock.baseUrl());

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(factory.created.get()).isEqualTo(1);
        wireMock.verify(23, postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(r.out()).contains("scored 23 of 23");
        JsonNode doc = JSON.readTree(results.toFile());
        JsonNode a = doc.path("answers");
        assertThat(a.path("scored").asInt()).isEqualTo(23);
        assertThat(a.path("judge").path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(a.path("judge").path("cap_usd").decimalValue()).isEqualByComparingTo("0.20");
        // 23 calls × (900 × $0.10 + 250 × $0.50) / 1M, at the committed file's prices
        assertThat(a.path("judge").path("spent_usd").decimalValue()).isEqualByComparingTo("0.004945");
        assertThat(a.path("unanswerable").path("refused").asInt()).isEqualTo(5);
        assertThat(new NetworkntOutputValidator(JSON).validate(Files.readString(AnswerEvalRunner.RESULTS_SCHEMA),
                doc.toString())).isEmpty();
        for (LoggedRequest req : wireMock.findAll(postRequestedFor(urlEqualTo("/v1/messages")))) {
            assertThat(req.getHeader("X-Api-Key")).isEqualTo(FAKE_KEY);
            assertThat(req.containsHeader("Authorization")).isFalse();
        }
    }

    @Test
    void refusesALoneSectionUnderEvalResultsAndACapAboveTheFile(@TempDir Path dir) throws Exception {
        Path answers = sample(dir, "hybrid");
        CountingFactory factory = new CountingFactory();
        Map<String, String> env = Map.of(JudgeSettings.API_KEY_ENV, FAKE_KEY);

        Run underResults = run(env, factory, "score", "--answers", answers.toString(), "--confirm-spend",
                "--out", "eval/results/answers-section.json", "--judge-base-url", wireMock.baseUrl());
        Run aboveCap = run(env, factory, "score", "--answers", answers.toString(), "--dry-run", "--cap-usd", "1.00");

        assertThat(underResults.code()).isEqualTo(2);
        assertThat(aboveCap.code()).isEqualTo(1);
        assertThat(aboveCap.out()).contains("above the file's cap");
        assertThat(factory.created.get()).isZero();
        assertThat(Files.exists(Path.of("eval/results/answers-section.json"))).isFalse();
    }

    @Test
    void ac_t13_8_unanswerableQuestionsAreRefusedAndCitationsStayWithinSources(@TempDir Path dir) throws Exception {
        // WireMock plays the gateway; its answers follow the RAG system prompt's instruction (D-49): "I don't know."
        // or the no-sources response for questions the corpus doesn't answer, cited answers otherwise.
        List<String> questions = new ArrayList<>();
        Map<String, String> bodies = new HashMap<>();
        for (int i = 1; i <= 3; i++) {
            String id = "q%02d".formatted(i);
            questions.add(question(id, i == 3 ? "exact_term" : "conceptual", "answerable question " + id));
            bodies.put("answerable question " + id, line(id, "conceptual", "hybrid", false).path("response").toString());
        }
        for (int i = 30; i < 35; i++) {
            String id = "q%02d".formatted(i);
            questions.add(question(id, "unanswerable", "unanswerable question " + id));
            JsonNode response = i % 2 == 0
                    ? line(id, "unanswerable", "hybrid", true).path("response")
                    : JSON.valueToTree(new RagAnswer(RagAnswer.NO_ANSWER_TEXT, List.of(), List.of(), true, "hybrid",
                    null, null, "req-" + id));
            bodies.put("unanswerable question " + id, response.toString());
        }
        questions.add(question("q40", "acl", "acl question q40"));
        Path qFile = dir.resolve("questions.jsonl");
        Files.write(qFile, questions, StandardCharsets.UTF_8);
        bodies.forEach((q, body) -> wireMock.stubFor(post("/v1/rag/query").withRequestBody(containing(q))
                .willReturn(ok(body))));
        Path out = dir.resolve("answers.jsonl");
        AnswerEvalRunner.ChunkTexts texts = ids -> {
            Map<String, String> m = new HashMap<>();
            ids.forEach(id -> m.put(id, "text of " + id));
            return m;
        };

        int code = AnswerEvalRunner.collect(AnswerEvalRunner.Options.parse(List.of("--questions", qFile.toString(),
                        "--out", out.toString(), "--gateway", wireMock.baseUrl())),
                Map.of(AnswerEvalRunner.GATEWAY_KEY_ENV, GATEWAY_KEY), new PrintStream(new ByteArrayOutputStream(),
                        true, StandardCharsets.UTF_8), texts);

        assertThat(code).isZero();
        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo("/v1/rag/query")));
        assertThat(requests).hasSize(8); // the acl question is the ACL eval's
        for (LoggedRequest req : requests) {
            assertThat(req.getHeader("Authorization")).isEqualTo("Bearer " + GATEWAY_KEY);
            JsonNode body = JSON.readTree(req.getBodyAsString());
            assertThat(body.path("collection").asText()).isEqualTo("k8s-c400");
            assertThat(body.path("top_k").asInt()).isEqualTo(5);
            assertThat(body.path("mode").asText()).isEqualTo("hybrid");
        }
        List<AnswerScorer.Item> items = AnswerEvalRunner.readItems(out);
        assertThat(items).hasSize(8);
        assertThat(items.get(0).sourceTexts().get("S1")).isEqualTo("text of 00000000-0000-0000-0000-000000000001");

        long unanswerable = items.stream().filter(AnswerScorer.Item::unanswerable).count();
        long refused = items.stream().filter(AnswerScorer.Item::unanswerable)
                .filter(i -> DeterministicChecks.isRefusal(i.response())).count();
        assertThat(unanswerable).isEqualTo(5);
        assertThat(refused).isEqualTo(5);
        assertThat(items).allMatch(i -> DeterministicChecks.citationsWithinSources(i.response()));
        assertThat(items.stream().filter(AnswerScorer.Item::answerable))
                .noneMatch(i -> DeterministicChecks.isRefusal(i.response()));
    }

    static String question(String id, String kind, String text) {
        ObjectNode q = JSON.createObjectNode();
        q.put("id", id);
        q.put("kind", kind);
        q.put("question", text);
        q.put("expected", "unanswerable".equals(kind) ? "refusal" : "answer");
        ArrayNode gold = q.putArray("gold");
        if (!"unanswerable".equals(kind)) gold.addObject().put("doc_id", "doc.1").put("evidence", "evidence passage for tests");
        return q.toString();
    }
}
