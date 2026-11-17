package dev.raggateway.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.raggateway.eval.judge.JudgeClient.JudgeCallException;
import dev.raggateway.eval.judge.JudgeClient.Outcome;
import dev.raggateway.eval.judge.JudgeClient.Prompt;
import dev.raggateway.eval.judge.JudgeClient.Rejected;
import dev.raggateway.eval.judge.JudgeClient.Scored;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.raggateway.eval.judge.JudgeStubs.FAKE_KEY;
import static dev.raggateway.eval.judge.JudgeStubs.JSON;
import static dev.raggateway.eval.judge.JudgeStubs.ok;
import static dev.raggateway.eval.judge.JudgeStubs.verdict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The judge client against WireMock (D-53). Nothing here reaches the real API: the base URL is WireMock's. */
class JudgeClientTest {

    static WireMockServer wireMock;
    final JudgeSettings settings = JudgeStubs.settings("0.50");
    final Prompt prompt = new Prompt("system text", "user text");

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

    JudgeClient client() {
        return JudgeClient.create(settings, wireMock.baseUrl(), Map.of(JudgeSettings.API_KEY_ENV, FAKE_KEY)::get);
    }

    Outcome judgeWith(String responseBody) {
        wireMock.stubFor(post("/v1/messages").willReturn(ok(responseBody)));
        try (JudgeClient c = client()) {
            return c.judge(prompt);
        }
    }

    @Test
    void ac_t13_7_requestAsksForAStructuredVerdictWithSettingsFromConfigAndNoSamplingParameters() throws Exception {
        judgeWith(JudgeStubs.textOnly(verdict(2, true), 10, 5));

        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(requests).hasSize(1);
        JsonNode body = JSON.readTree(requests.get(0).getBodyAsString());
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(1000);
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        JsonNode format = body.path("output_config").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        JsonNode schema = format.path("schema");
        assertThat(schema.path("properties").has("correctness")).isTrue();
        assertThat(schema.path("properties").has("grounded")).isTrue();
        assertThat(schema.path("properties").has("reason")).isTrue();
        assertThat(body.path("system").toString()).contains("system text");
        assertThat(body.path("messages").toString()).contains("user text");
        // Claude Haiku 5.5 rejects non-default sampling; thinking is left at the model's adaptive default
        for (String absent : List.of("temperature", "top_p", "top_k", "thinking")) {
            assertThat(body.has(absent)).as(absent).isFalse();
        }
    }

    @Test
    void sendsTheKeyAsXApiKeyAndNoAuthorizationHeader() {
        judgeWith(JudgeStubs.textOnly(verdict(2, true), 10, 5));

        LoggedRequest request = wireMock.findAll(postRequestedFor(urlEqualTo("/v1/messages"))).get(0);
        assertThat(request.getHeader("X-Api-Key")).isEqualTo(FAKE_KEY);
        assertThat(request.containsHeader("Authorization")).isFalse();
    }

    @Test
    void readsTheKeyFromEvalJudgeApiKeyOnly() {
        List<String> asked = new ArrayList<>();
        Map<String, String> env = Map.of(
                "GATEWAY_CLAUDE_API_KEY", "test-gateway-key-not-real",
                "ANTHROPIC_API_KEY", "test-anthropic-key-not-real");
        assertThatThrownBy(() -> JudgeClient.create(settings, wireMock.baseUrl(), name -> {
            asked.add(name);
            return env.get(name);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining(JudgeSettings.API_KEY_ENV);
        assertThat(asked).containsExactly(JudgeSettings.API_KEY_ENV);
        assertThat(wireMock.getAllServeEvents()).isEmpty();

        // a settings file naming another variable is refused before any client exists
        assertThatThrownBy(() -> new JudgeSettings("claude-haiku-5-5", settings.inputUsdPerMtok(),
                settings.outputUsdPerMtok(), "x", "low", 1000, settings.capUsd(), "GATEWAY_CLAUDE_API_KEY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsContentBlocksByTypeWhenAThinkingBlockComesFirst() {
        Outcome outcome = judgeWith(JudgeStubs.thinkingThenText(verdict(1, false), 1200, 300));

        assertThat(outcome).isInstanceOf(Scored.class);
        JudgeVerdict v = ((Scored) outcome).verdict();
        assertThat(v.correctness()).isEqualTo(1);
        assertThat(v.grounded()).isFalse();
        assertThat(v.reason()).isEqualTo("test verdict");
        assertThat(outcome.usage()).isEqualTo(new JudgeClient.Usage(1200, 300));
    }

    @Test
    void ac_t13_7_rejectsMalformedAndOutOfRangeVerdictsAndKeepsTheirUsage() {
        List<String> badTexts = List.of(
                "not json at all",
                "{\"correctness\":2,\"grounded\":true",                                // cut off
                verdict(3, true),                                                      // out of range
                verdict(-1, true),
                "{\"correctness\":1.5,\"grounded\":true,\"reason\":\"x\"}",           // not an integer
                "{\"correctness\":2,\"grounded\":\"yes\",\"reason\":\"x\"}",          // not a boolean
                "{\"correctness\":2,\"grounded\":true}",                               // no reason
                "{\"correctness\":2,\"grounded\":true,\"reason\":\"\"}",              // empty reason
                "{\"correctness\":2,\"grounded\":true,\"reason\":\"x\",\"extra\":1}", // unknown field
                verdict(2, true) + " trailing");
        for (String text : badTexts) {
            wireMock.resetAll();
            Outcome outcome = judgeWith(JudgeStubs.thinkingThenText(text, 100, 40));
            assertThat(outcome).as(text).isInstanceOf(Rejected.class);
            assertThat(outcome.usage()).as(text).isEqualTo(new JudgeClient.Usage(100, 40));
        }
    }

    @Test
    void rejectsRefusalsTruncationAndRepliesWithoutExactlyOneTextBlock() {
        assertThat(judgeWith(JudgeStubs.message(List.of(JudgeStubs.textBlock(verdict(2, true))), "refusal", 50, 5)))
                .isInstanceOf(Rejected.class);
        assertThat(judgeWith(JudgeStubs.message(List.of(JudgeStubs.thinking()), "max_tokens", 50, 1000)))
                .isInstanceOf(Rejected.class);
        assertThat(judgeWith(JudgeStubs.message(List.of(JudgeStubs.thinking()), "end_turn", 50, 10)))
                .isInstanceOf(Rejected.class);
        assertThat(judgeWith(JudgeStubs.message(List.of(JudgeStubs.textBlock(verdict(2, true)),
                JudgeStubs.textBlock(verdict(0, false))), "end_turn", 50, 10))).isInstanceOf(Rejected.class);
    }

    @Test
    void anHttpErrorIsACallFailureAndIsNotRetried() {
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"test\"}}")));
        try (JudgeClient c = client()) {
            assertThatThrownBy(() -> c.judge(prompt)).isInstanceOf(JudgeCallException.class)
                    .hasMessageContaining("500");
        }
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }
}
