package dev.raggateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.OllamaNdjson;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * t03 end to end over real HTTP: the whole application with WireMock playing both the Claude Messages API
 * ({@link ClaudeSse}) and Ollama {@code /api/chat} ({@link OllamaNdjson}), through the fallback router, the real
 * adapters and their watchdogs (AC-t03-1, -2, -3, -6, -7, -8, -10). The router's breaker and retry details are
 * unit-tested in {@code FallbackRouterTest}.
 *
 * <p>Claude's first-token timeout is 1 s here (production: 5 s), so AC-t03-2 runs in a few seconds; the first
 * HTTP client call in a fresh JVM can take about 0.5 s, so that test warms both adapters up first.
 */
class FallbackE2EIT extends AbstractIntegrationTest {

    static final String CLAUDE_MODEL = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final Duration CLAUDE_FIRST_TOKEN_TIMEOUT = Duration.ofSeconds(1);
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    static final WireMockServer OLLAMA = startWireMock();

    // Obviously fake gateway key, created for this test only.
    static final String KEY = "rgw_test_fallback_key_" + UUID.randomUUID().toString().replace("-", "");

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<UUID> sentRequestIds = new ArrayList<>();

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void providersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
        registry.add("gateway.providers.claude.first-token-timeout", CLAUDE_FIRST_TOKEN_TIMEOUT::toString);
        registry.add("gateway.providers.ollama.base-url", OLLAMA::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        CLAUDE.stop();
        OLLAMA.stop();
    }

    @BeforeEach
    void seed() {
        CLAUDE.resetAll();
        OLLAMA.resetAll();
        TestData.insertTenant(jdbc, "acme", 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY, Set.of(), false);
    }

    // ---- helpers ----

    static String body(String model, boolean stream, String prompt) {
        return """
                {"model":"%s","stream":%s,"max_tokens":64,
                 "messages":[{"role":"user","content":"%s"}]}""".formatted(model, stream, prompt);
    }

    HttpResponse<String> chat(String model, boolean stream) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + KEY)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body(model, stream, "hi"))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue(GatewayHeaders.REQUEST_ID).map(UUID::fromString).ifPresent(sentRequestIds::add);
        return response;
    }

    static void claudeReturns(ResponseDefinitionBuilder response) {
        CLAUDE.stubFor(post("/v1/messages").willReturn(response));
    }

    static void ollamaReturns(ResponseDefinitionBuilder response) {
        OLLAMA.stubFor(post("/api/chat").willReturn(response));
    }

    static ResponseDefinitionBuilder claudeSse(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(body);
    }

    static ResponseDefinitionBuilder claudeError(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"fake " + status + "\"}}");
    }

    static ResponseDefinitionBuilder ollamaReply(String... pieces) {
        return aResponse().withStatus(200).withHeader("Content-Type", OllamaNdjson.CONTENT_TYPE)
                .withBody(OllamaNdjson.reply(LLAMA, 12, pieces.length, pieces));
    }

    static void verifyCalls(int claude, int ollama) {
        CLAUDE.verify(claude, postRequestedFor(urlEqualTo("/v1/messages")));
        OLLAMA.verify(ollama, postRequestedFor(urlEqualTo("/api/chat")));
    }

    static List<JsonNode> dataEvents(String sse) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : sse.split("\n")) {
            if (line.startsWith("data: {")) events.add(JSON.readTree(line.substring(6)));
        }
        return events;
    }

    static String streamedText(String sse) throws Exception {
        var text = new StringBuilder();
        for (JsonNode e : dataEvents(sse)) {
            JsonNode choices = e.path("choices");
            if (choices.isArray() && !choices.isEmpty()) text.append(choices.get(0).path("delta").path("content").asText(""));
        }
        return text.toString();
    }

    /** The log row of one of this test's responses, selected by its own request id (TEAM_RULES). */
    Map<String, Object> awaitLogRow(HttpResponse<String> response) {
        UUID id = UUID.fromString(response.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow());
        await().atMost(Duration.ofSeconds(5)).until(() -> !rows(id).isEmpty());
        List<Map<String, Object>> rows = rows(id);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> rows(UUID id) {
        return jdbc.sql("SELECT * FROM request_log WHERE request_id = ?").param(id).query().listOfRows();
    }

    String scrape() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    static int intValue(Object o) {
        return ((Number) o).intValue();
    }

    // ---- AC-t03-1 ----

    @Test
    void ac_t03_1_primaryReturns500TwiceSoTheFallbackAnswersAndThePrimaryGotExactlyTwoCalls() throws Exception {
        claudeReturns(claudeError(500));
        ollamaReturns(ollamaReply("from ", "llama"));

        HttpResponse<String> response = chat(CLAUDE_MODEL, true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(streamedText(response.body())).isEqualTo("from llama");
        assertThat(response.body()).contains("data: [DONE]");
        verifyCalls(2, 1);
        Map<String, Object> row = awaitLogRow(response);
        assertThat(row).containsEntry("status", "ok").containsEntry("fallback_used", true);
        assertThat(intValue(row.get("attempts"))).isEqualTo(3);
    }

    // ---- AC-t03-2 ----

    @Test
    void ac_t03_2_primarySendsNoFirstTokenInTimeSoTheFallbackAnswersWithinTheBound() throws Exception {
        // Warm both adapters up: the first client call in a fresh JVM is slow, which is not what this test measures.
        claudeReturns(claudeSse(ClaudeSse.reply(CLAUDE_MODEL, 5, 1, "warm")));
        ollamaReturns(ollamaReply("warm"));
        assertThat(chat(CLAUDE_MODEL, true).statusCode()).isEqualTo(200);
        assertThat(chat(LLAMA, true).statusCode()).isEqualTo(200);
        CLAUDE.resetAll();
        OLLAMA.resetAll();
        // Headers and the reply arrive only after 5 s: far beyond the 1 s first-token timeout.
        claudeReturns(claudeSse(ClaudeSse.reply(CLAUDE_MODEL, 5, 1, "late")).withFixedDelay(5_000));
        ollamaReturns(ollamaReply("from ", "llama"));

        long start = System.nanoTime();
        HttpResponse<String> response = chat(CLAUDE_MODEL, true);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(streamedText(response.body())).isEqualTo("from llama");
        assertThat(response.headers().firstValue(GatewayHeaders.PROVIDER)).hasValue("ollama");
        verifyCalls(2, 1);
        // Bound from the plan: first-token timeout × attempts + backoff cap + 1 s = 1 s × 2 + 2 s + 1 s.
        Duration bound = CLAUDE_FIRST_TOKEN_TIMEOUT.multipliedBy(2).plus(Duration.ofSeconds(2)).plusSeconds(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(CLAUDE_FIRST_TOKEN_TIMEOUT.multipliedBy(2)).isLessThan(bound);
        Map<String, Object> row = awaitLogRow(response);
        assertThat(row).containsEntry("provider_used", "ollama").containsEntry("fallback_used", true);
        assertThat(intValue(row.get("attempts"))).isEqualTo(3);
    }

    // ---- AC-t03-3 ----

    @Test
    void ac_t03_3_primaryFailsAfterAFirstTokenSoNoRetryNoFallbackAndNoRepeatedText() throws Exception {
        claudeReturns(claudeSse(ClaudeSse.start(CLAUDE_MODEL, 10).textBlockStart().text("partial ")
                .error("overloaded_error", "busy").body()));
        ollamaReturns(ollamaReply("from ", "llama"));

        HttpResponse<String> response = chat(CLAUDE_MODEL, true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(streamedText(response.body())).isEqualTo("partial ");
        assertThat(response.body().split("partial ", -1)).as("the text appears once").hasSize(2);
        List<JsonNode> events = dataEvents(response.body());
        assertThat(events.get(events.size() - 1).path("error").path("code").asText()).isEqualTo("provider_error");
        assertThat(response.body()).doesNotContain("[DONE]");
        verifyCalls(1, 0);
        Map<String, Object> row = awaitLogRow(response);
        assertThat(row).containsEntry("status", "error").containsEntry("provider_used", "claude")
                .containsEntry("fallback_used", false);
        assertThat(intValue(row.get("attempts"))).isEqualTo(1);
    }

    // ---- AC-t03-6 ----

    @Test
    void ac_t03_6_primaryReturns400SoTheClientGets400AndTheFallbackGetsNoCall() throws Exception {
        claudeReturns(claudeError(400));
        ollamaReturns(ollamaReply("from ", "llama"));

        HttpResponse<String> response = chat(CLAUDE_MODEL, false);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asText()).isEqualTo("upstream_rejected");
        verifyCalls(1, 0);
    }

    // ---- AC-t03-7 ----

    @Test
    void ac_t03_7_bothProvidersFailingIsA503JsonErrorAndOneFailedLogRow() throws Exception {
        claudeReturns(claudeError(500));
        ollamaReturns(aResponse().withStatus(503).withHeader("Content-Type", "application/json")
                .withBody(OllamaNdjson.errorBody("fake overload")));

        HttpResponse<String> response = chat(CLAUDE_MODEL, true);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode error = JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("all_providers_failed");
        assertThat(error.path("request_id").asText())
                .isEqualTo(response.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow());
        assertThat(error.path("message").asText()).doesNotContain("fake overload");
        verifyCalls(2, 2);
        Map<String, Object> row = awaitLogRow(response);
        assertThat(row).containsEntry("status", "error").containsEntry("error_code", "all_providers_failed");
        assertThat(intValue(row.get("http_status"))).isEqualTo(503);
        assertThat(intValue(row.get("attempts"))).isEqualTo(4);
        assertThat(intValue(row.get("input_tokens"))).as("HTTP errors bill nothing (D-34)").isZero();
    }

    // ---- AC-t03-8 ----

    @Test
    void ac_t03_8_whenTheFallbackAnswersTheBodyHeadersAndLogRowNameTheFallback() throws Exception {
        claudeReturns(claudeError(529));
        ollamaReturns(ollamaReply("from ", "llama"));

        HttpResponse<String> json = chat(CLAUDE_MODEL, false);
        HttpResponse<String> sse = chat(CLAUDE_MODEL, true);

        assertThat(json.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(json.body()).path("model").asText()).isEqualTo(LLAMA);
        assertThat(JSON.readTree(json.body()).at("/choices/0/message/content").asText()).isEqualTo("from llama");
        assertThat(sse.statusCode()).isEqualTo(200);
        assertThat(dataEvents(sse.body())).allSatisfy(e -> assertThat(e.path("model").asText()).isEqualTo(LLAMA));
        for (HttpResponse<String> response : List.of(json, sse)) {
            assertThat(response.headers().firstValue(GatewayHeaders.PROVIDER)).hasValue("ollama");
            assertThat(response.headers().firstValue(GatewayHeaders.MODEL)).hasValue(LLAMA);
            Map<String, Object> row = awaitLogRow(response);
            assertThat(row).containsEntry("provider_used", "ollama").containsEntry("model_used", LLAMA)
                    .containsEntry("requested_model", CLAUDE_MODEL).containsEntry("fallback_used", true)
                    .containsEntry("status", "ok");
        }
    }

    // ---- AC-t03-10 (with AC-t03-4 over HTTP) ----

    @Test
    void ac_t03_10_breakerStatePerProviderIsOnThePrometheusEndpointAndFollowsTheTraffic() throws Exception {
        assertThat(scrape())
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"claude\"[^}]*state=\"closed\"[^}]*} 1\\.0")
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"ollama\"[^}]*state=\"closed\"[^}]*} 1\\.0");
        claudeReturns(claudeError(500));
        ollamaReturns(ollamaReply("ok"));

        // 10 requests × 2 failed attempts fill the window of 20 with failures.
        for (int i = 0; i < 10; i++) assertThat(chat(CLAUDE_MODEL, false).statusCode()).isEqualTo(200);
        verifyCalls(20, 10);

        assertThat(scrape())
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"claude\"[^}]*state=\"open\"[^}]*} 1\\.0")
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"ollama\"[^}]*state=\"closed\"[^}]*} 1\\.0");
        // While it is open, the primary gets no calls (AC-t03-4).
        for (int i = 0; i < 10; i++) assertThat(chat(CLAUDE_MODEL, false).statusCode()).isEqualTo(200);
        verifyCalls(20, 20);
    }
}
