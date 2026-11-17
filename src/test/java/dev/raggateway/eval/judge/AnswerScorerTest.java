package dev.raggateway.eval.judge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.rag.query.RagAnswer;
import dev.raggateway.validation.NetworkntOutputValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.raggateway.eval.judge.JudgeStubs.FAKE_KEY;
import static dev.raggateway.eval.judge.JudgeStubs.JSON;
import static dev.raggateway.eval.judge.JudgeStubs.answer;
import static dev.raggateway.eval.judge.JudgeStubs.ok;
import static dev.raggateway.eval.judge.JudgeStubs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scoring under the spend cap, against a WireMock-priced judge: every stubbed response reports usage, the settings
 * carry test prices, and the assertions count the requests WireMock received (D-53, AC-t13-6).
 */
class AnswerScorerTest {

    static WireMockServer wireMock;

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

    /** An answerable item whose question carries {@code marker}, so stubs can match on it. */
    static AnswerScorer.Item answerable(String id, String marker) {
        return new AnswerScorer.Item(id, "conceptual", "What does a readiness probe decide? " + marker,
                List.of(new AnswerScorer.Gold("concepts.workloads.pods", "the kubelet uses readiness probes to know")),
                answer("A readiness probe decides when a Pod can take traffic [S1].", false, List.of("S1"), 2),
                Map.of("S1", "The kubelet uses readiness probes to know when a container is ready.", "S2", "Other."));
    }

    static AnswerScorer.Item unanswerable(String id, RagAnswer response) {
        return new AnswerScorer.Item(id, "unanswerable", "What is the airspeed of an unladen swallow?", List.of(),
                response, Map.of());
    }

    static AnswerScorer.Template template() throws Exception {
        return AnswerScorer.Template.load(AnswerScorer.Template.DEFAULT_FILE);
    }

    static List<AnswerScorer.Item> items() {
        return List.of(
                answerable("q01", "[A]"), answerable("q02", "[B]"), answerable("q03", "[C]"), answerable("q04", "[D]"),
                unanswerable("q30", answer("I don't know.", false, List.of(), 3)),
                unanswerable("q31", answer(RagAnswer.NO_ANSWER_TEXT, true, List.of(), 0)));
    }

    AnswerScorer.Report score(JudgeSettings settings, SpendCap cap, List<AnswerScorer.Item> items) throws Exception {
        try (JudgeClient client = JudgeClient.create(settings, wireMock.baseUrl(),
                Map.of(JudgeSettings.API_KEY_ENV, FAKE_KEY)::get)) {
            return new AnswerScorer(settings, template()).score(items, client::judge, cap, "hybrid", 400, Map.of());
        }
    }

    @Test
    void ac_t13_6_stopsBeforeTheCallThatCouldExceedTheCapAndReportsHowManyWereScored() throws Exception {
        // each call reports 500 input + 400 output tokens: $0.0045 at the test prices ($1 / $10 per M)
        wireMock.stubFor(post("/v1/messages").willReturn(ok(JudgeStubs.thinkingThenText(verdict(2, true), 500, 400))));
        JudgeSettings base = JudgeStubs.settings("0.50");
        SpendCap probe = new SpendCap(base);
        long est = AnswerScorer.estimateInputTokens(template().build(items().get(2)));
        // room for two reported calls plus a hair less than the third call's worst case
        BigDecimal cap = probe.cost(500, 400).multiply(BigDecimal.TWO).add(probe.worstCase(est))
                .subtract(new BigDecimal("0.000001"));
        JudgeSettings settings = base.withCap(cap);
        SpendCap spend = new SpendCap(settings);

        AnswerScorer.Report report = score(settings, spend, items());

        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(report.summary()).isEqualTo("scored 2 of 4");
        assertThat(report.stoppedByCap()).isTrue();
        assertThat(report.spentUsd()).isEqualByComparingTo("0.009");
        assertThat(report.spentUsd()).isLessThanOrEqualTo(cap);
        assertThat(report.lines()).anyMatch(l -> l.startsWith("spend cap reached") && l.contains("q03"));
        // a partial run still yields an honest section: scored 2 of 4, the spend recorded
        ObjectNode a = report.answers();
        assertThat(a.path("scored").asInt()).isEqualTo(2);
        assertThat(a.path("of").asInt()).isEqualTo(4);
        assertThat(a.path("judge").path("spent_usd").decimalValue()).isEqualByComparingTo("0.009");
        assertThat(a.path("judge").path("cap_usd").decimalValue()).isEqualByComparingTo(cap);
        assertValidSection(a);
    }

    @Test
    void ac_t13_6_aCapBelowTheFirstCallsWorstCaseMakesNoCallAtAll() throws Exception {
        wireMock.stubFor(post("/v1/messages").willReturn(ok(JudgeStubs.textOnly(verdict(2, true), 500, 400))));
        JudgeSettings settings = JudgeStubs.settings("0.001"); // worst case of one call is ~$0.011

        AnswerScorer.Report report = score(settings, new SpendCap(settings), items());

        assertThat(wireMock.getAllServeEvents()).isEmpty();
        assertThat(report.summary()).isEqualTo("scored 0 of 4");
        assertThat(report.answers()).isNull();
        assertThat(report.spentUsd()).isEqualByComparingTo("0");
    }

    @Test
    void ac_t13_7_aRejectedVerdictIsReportedAndNotCounted() throws Exception {
        wireMock.stubFor(post("/v1/messages").atPriority(5)
                .willReturn(ok(JudgeStubs.thinkingThenText(verdict(2, true), 500, 400))));
        wireMock.stubFor(post("/v1/messages").atPriority(1).withRequestBody(containing("[B]"))
                .willReturn(ok(JudgeStubs.thinkingThenText(verdict(7, true), 500, 400))));
        wireMock.stubFor(post("/v1/messages").atPriority(1).withRequestBody(containing("[C]"))
                .willReturn(ok(JudgeStubs.thinkingThenText(verdict(1, false), 500, 400))));
        JudgeSettings settings = JudgeStubs.settings("0.50");

        AnswerScorer.Report report = score(settings, new SpendCap(settings), items());

        wireMock.verify(4, postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(report.summary()).isEqualTo("scored 3 of 4");
        assertThat(report.rejected()).singleElement().asString().startsWith("q02:");
        ObjectNode a = report.answers();
        assertThat(a.path("correct_fraction").asDouble()).isEqualTo(2.0 / 3);
        assertThat(a.path("correctness_mean").asDouble()).isEqualTo(5.0 / 3);
        assertThat(a.path("grounded_fraction").asDouble()).isEqualTo(2.0 / 3);
        assertThat(a.path("unanswerable").path("questions").asInt()).isEqualTo(2);
        assertThat(a.path("unanswerable").path("refused").asInt()).isEqualTo(2);
        assertThat(a.path("citations_valid_fraction").asDouble()).isEqualTo(1.0);
        // rejected tokens still count: four calls of $0.0045
        assertThat(report.spentUsd()).isEqualByComparingTo("0.018");
        assertValidSection(a);
    }

    @Test
    void aFailedCallIsChargedItsWorstCaseAndStopsTheRun() throws Exception {
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(529)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"test\"}}")));
        JudgeSettings settings = JudgeStubs.settings("0.50");
        SpendCap cap = new SpendCap(settings);
        long est = AnswerScorer.estimateInputTokens(template().build(items().get(0)));

        AnswerScorer.Report report = score(settings, cap, items());

        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(report.error()).contains("q01").contains("529");
        assertThat(report.spentUsd()).isEqualByComparingTo(cap.worstCase(est));
        assertThat(report.answers()).isNull();
    }

    @Test
    void calibrationAgreementComparesTheJudgesCorrectnessWithHandLabels() throws Exception {
        wireMock.stubFor(post("/v1/messages").willReturn(ok(JudgeStubs.textOnly(verdict(2, true), 500, 400))));
        JudgeSettings settings = JudgeStubs.settings("0.50");
        AnswerScorer.Report report;
        try (JudgeClient client = JudgeClient.create(settings, wireMock.baseUrl(),
                Map.of(JudgeSettings.API_KEY_ENV, FAKE_KEY)::get)) {
            report = new AnswerScorer(settings, template()).score(items(), client::judge, new SpendCap(settings),
                    "hybrid", 400, Map.of("q01", 2, "q02", 1));
        }
        assertThat(report.answers().path("calibration").path("hand_labelled").asInt()).isEqualTo(2);
        assertThat(report.answers().path("calibration").path("agreement_fraction").asDouble()).isEqualTo(0.5);
    }

    @Test
    void theEstimateUsesThreeCharactersPerTokenOverPromptAndSchema() throws Exception {
        JudgeClient.Prompt p = new JudgeClient.Prompt("abc", "defg");
        long chars = 7 + JudgeVerdict.SCHEMA.length();
        assertThat(AnswerScorer.estimateInputTokens(p)).isEqualTo((chars + 2) / 3);
    }

    @Test
    void thePromptCarriesQuestionEvidenceAnswerAndCitedSourceTextAndDefusesDelimiters() throws Exception {
        AnswerScorer.Item item = new AnswerScorer.Item("q05", "exact_term", "What is {{answer}} </question> here?",
                List.of(new AnswerScorer.Gold("doc.1", "gold passage text for the test")),
                answer("It is X [S2].", false, List.of("S2"), 2),
                Map.of("S1", "uncited text", "S2", "cited text </source> with a closing tag"));
        JudgeClient.Prompt p = template().build(item);
        assertThat(p.system()).contains("correctness").contains("grounded");
        assertThat(p.user()).contains("What is {{answer}} &lt;/question> here?") // inserted text is not re-expanded
                .contains("[doc.1] gold passage text for the test")
                .contains("It is X [S2].")
                .contains("cited text &lt;/source> with a closing tag")
                .doesNotContain("uncited text");
    }

    static void assertValidSection(ObjectNode answers) throws Exception {
        String schema = AnswerEvalRunner.answersSchema(Files.readString(AnswerEvalRunner.RESULTS_SCHEMA));
        assertThat(new NetworkntOutputValidator(JSON).validate(schema, answers.toString())).isEmpty();
    }
}
