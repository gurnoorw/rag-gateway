package dev.raggateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * t01 end to end over real HTTP: the whole application (edge, pipeline, Claude adapter on the SDK, key store,
 * request log) with WireMock playing the Claude Messages API. AC-t01-9 (client disconnect) needs to see the
 * upstream socket close, which WireMock cannot report; it is in {@link ChatCompletionsCancelE2EIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ChatCompletionsE2EIT extends AbstractIntegrationTest {

    static final String MODEL = "claude-haiku-5-5";
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();

    // Obviously fake gateway key, created for this test only.
    static final String KEY = "rgw_test_e2e_key_" + UUID.randomUUID().toString().replace("-", "");

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void claudeAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        CLAUDE.stop();
    }

    @BeforeEach
    void seed() {
        CLAUDE.resetAll();
        TestData.insertTenant(jdbc, "acme", 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY, Set.of("eng"), false);
    }

    // ---- helpers ----

    static String body(boolean stream, String prompt) {
        return """
                {"model":"%s","stream":%s,"max_tokens":64,"stream_options":%s,
                 "messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"%s"}]}"""
                .formatted(MODEL, stream, stream ? "{\"include_usage\":true}" : "null", prompt);
    }

    HttpRequest.Builder chat(String json) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    /** Request ids of the responses this test received; earlier tests' late log rows are ignored. */
    private final List<UUID> sentRequestIds = new ArrayList<>();

    HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("X-Request-Id").map(UUID::fromString).ifPresent(sentRequestIds::add);
        return response;
    }

    static void stubClaude(String sseBody) {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream").withBody(sseBody)));
    }

    static List<JsonNode> dataEvents(String sse) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : sse.split("\n")) {
            if (line.startsWith("data: {")) events.add(JSON.readTree(line.substring(6)));
        }
        return events;
    }

    /**
     * The one log row written for this test's requests. Rows are written after the response is closed (C7),
     * so a row can appear just after the client returns, and a previous test's row can land after this
     * test's cleanup; filtering on this test's request ids keeps the check exact.
     */
    Map<String, Object> awaitSingleLogRow() {
        await().atMost(Duration.ofSeconds(5)).until(() -> !ownLogRows().isEmpty());
        List<Map<String, Object>> rows = ownLogRows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    List<Map<String, Object>> ownLogRows() {
        return jdbc.sql("SELECT * FROM request_log WHERE request_id = ANY(?)")
                .param(sentRequestIds.toArray(UUID[]::new)).query().listOfRows();
    }

    /** Every row of every application table, as JSON text, for "is this string stored anywhere" checks. */
    String allTablesAsText() {
        List<String> tables = jdbc.sql("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """).query(String.class).list();
        StringBuilder all = new StringBuilder();
        for (String t : tables) {
            all.append(jdbc.sql("SELECT coalesce(json_agg(x)::text, '') FROM " + t + " x").query(String.class).single());
        }
        return all.toString();
    }

    // ---- AC-t01-1 ----

    @Test
    void ac_t01_1_streamReturnsEventStreamWithChunksThenDone() throws Exception {
        stubClaude(ClaudeSse.reply(MODEL, 20, 6, "Hel", "lo ", "there"));

        HttpResponse<String> response = send(chat(body(true, "hi")).header("Authorization", "Bearer " + KEY).build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("text/event-stream"));
        assertThat(response.headers().firstValue("X-Gateway-Provider")).contains("claude");
        assertThat(response.headers().firstValue("X-Gateway-Model")).contains(MODEL);
        List<JsonNode> events = dataEvents(response.body());
        List<String> contents = events.stream()
                .filter(e -> e.path("choices").size() > 0)
                .map(e -> e.path("choices").get(0).path("delta").path("content").asText(null))
                .filter(c -> c != null)
                .toList();
        assertThat(contents).hasSizeGreaterThanOrEqualTo(2).containsExactly("Hel", "lo ", "there");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.path("object").asText()).isEqualTo("chat.completion.chunk");
            assertThat(e.path("id").asText()).startsWith("chatcmpl-");
        });
        assertThat(events).anySatisfy(e -> assertThat(
                e.path("choices").path(0).path("finish_reason").asText()).isEqualTo("stop"));
        assertThat(events).anySatisfy(e -> assertThat(e.path("usage").path("prompt_tokens").asInt()).isEqualTo(20));
        assertThat(response.body().strip()).endsWith("data: [DONE]");
    }

    // ---- AC-t01-2 ----

    @Test
    void ac_t01_2_nonStreamReturnsJsonCompletionWithUsage() throws Exception {
        stubClaude(ClaudeSse.reply(MODEL, 20, 6, "Hel", "lo"));

        HttpResponse<String> response = send(chat(body(false, "hi")).header("Authorization", "Bearer " + KEY).build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode json = JSON.readTree(response.body());
        assertThat(json.path("object").asText()).isEqualTo("chat.completion");
        assertThat(json.path("choices").get(0).path("message").path("content").asText()).isEqualTo("Hello");
        assertThat(json.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
        assertThat(json.path("usage").path("prompt_tokens").asInt()).isEqualTo(20);
        assertThat(json.path("usage").path("completion_tokens").asInt()).isEqualTo(6);
        assertThat(json.path("model").asText()).isEqualTo(MODEL);
    }

    // ---- AC-t01-3 ----

    @Test
    void ac_t01_3_missingOrUnknownKeyIs401AndNoProviderCall() throws Exception {
        stubClaude(ClaudeSse.reply(MODEL, 1, 1, "x"));

        HttpResponse<String> missing = send(chat(body(true, "hi")).build());
        HttpResponse<String> unknown = send(chat(body(true, "hi"))
                .header("Authorization", "Bearer rgw_test_unknown_key_not_secret").build());

        for (HttpResponse<String> r : List.of(missing, unknown)) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                    ct -> assertThat(ct).startsWith("application/json"));
            JsonNode error = JSON.readTree(r.body()).path("error");
            assertThat(error.path("type").asText()).isEqualTo("authentication_error");
            assertThat(error.path("code").asText()).isEqualTo("unauthorized");
        }
        CLAUDE.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(ownLogRows()).as("rejected at the edge: no tenant context, no log row (C7)").isEmpty();
    }

    // ---- AC-t01-5 ----

    @Test
    void ac_t01_5_firstChunkArrivesBeforeTheMockHasSentItsLastChunk() throws Exception {
        ClaudeSse sse = ClaudeSse.start(MODEL, 10).textBlockStart();
        for (int i = 0; i < 10; i++) sse.text("t" + i + " ");
        String upstream = sse.blockStop().end("end_turn", 10).body();
        // WireMock sends the body in 20 pieces spread over 2 s.
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream").withBody(upstream)
                .withChunkedDribbleDelay(20, 2000)));

        HttpResponse<InputStream> response = http.send(chat(body(true, "hi"))
                .header("Authorization", "Bearer " + KEY).build(), HttpResponse.BodyHandlers.ofInputStream());

        long firstContentAt = -1;
        long doneAt = -1;
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstContentAt < 0 && line.contains("\"content\":\"t0 \"")) firstContentAt = System.nanoTime();
                if (line.equals("data: [DONE]")) doneAt = System.nanoTime();
            }
        }
        assertThat(firstContentAt).as("first content chunk seen").isPositive();
        assertThat(doneAt).as("[DONE] seen").isPositive();
        // Buffering would deliver everything together at the end; streaming spreads it over the upstream's 2 s.
        assertThat(Duration.ofNanos(doneAt - firstContentAt)).isGreaterThan(Duration.ofMillis(800));
    }

    // ---- AC-t01-6 ----

    @Test
    void ac_t01_6_oneLogRowWithTheResponseRequestIdTokensAndTenant() throws Exception {
        stubClaude(ClaudeSse.reply(MODEL, 20, 6, "Hel", "lo"));

        HttpResponse<String> response = send(chat(body(true, "hi")).header("Authorization", "Bearer " + KEY).build());

        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        Map<String, Object> row = awaitSingleLogRow();
        assertThat(row.get("request_id")).isEqualTo(UUID.fromString(requestId));
        assertThat(row).containsEntry("tenant_id", "acme")
                .containsEntry("status", "ok")
                .containsEntry("provider_used", "claude")
                .containsEntry("model_used", MODEL)
                .containsEntry("stream", true)
                .containsEntry("input_tokens", 20)
                .containsEntry("output_tokens", 6)
                .containsEntry("usage_estimated", false);
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(200);
        assertThat(row.get("ttft_ms")).isNotNull();
    }

    // ---- AC-t01-7 ----

    @Test
    void ac_t01_7_noLogColumnContainsThePromptText() throws Exception {
        String marker = "prompt-marker-" + UUID.randomUUID();
        stubClaude(ClaudeSse.reply(MODEL, 20, 6, "ok"));

        HttpResponse<String> response = send(chat(body(false, "please repeat " + marker))
                .header("Authorization", "Bearer " + KEY).build());

        assertThat(response.statusCode()).isEqualTo(200);
        CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))); // the prompt did go upstream
        awaitSingleLogRow();
        String rowText = jdbc.sql("SELECT row_to_json(r)::text FROM request_log r WHERE request_id = ?")
                .param(awaitSingleLogRow().get("request_id")).query(String.class).single();
        assertThat(rowText).doesNotContain(marker);
    }

    // ---- AC-t01-8 ----

    @Test
    void ac_t01_8_plaintextKeyIsInNoTableAndNoLogLine(CapturedOutput output) throws Exception {
        stubClaude(ClaudeSse.reply(MODEL, 20, 6, "ok"));
        String unknownKey = "rgw_test_unknown_" + UUID.randomUUID().toString().replace("-", "");

        assertThat(send(chat(body(true, "hi")).header("Authorization", "Bearer " + KEY).build()).statusCode())
                .isEqualTo(200);
        assertThat(send(chat(body(true, "hi")).header("Authorization", "Bearer " + unknownKey).build()).statusCode())
                .isEqualTo(401);
        awaitSingleLogRow();

        String stored = allTablesAsText();
        assertThat(stored).contains(TestData.sha256Hex(KEY)).doesNotContain(KEY).doesNotContain(unknownKey);
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain(unknownKey);
    }

    // ---- AC-t01-10 ----

    @Test
    void ac_t01_10_providerFailureBeforeFirstTokenIsAJsonErrorStatus() throws Exception {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"upstream broke\"}}")));

        HttpResponse<String> response = send(chat(body(true, "hi")).header("Authorization", "Bearer " + KEY).build());

        // From t03 the router retries the 500 and tries the fallback chain first: all failed is a 503.
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode error = JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("all_providers_failed");
        assertThat(error.path("request_id").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        Map<String, Object> row = awaitSingleLogRow();
        assertThat(row).containsEntry("status", "error").containsEntry("error_code", "all_providers_failed");
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(response.statusCode());
    }

    @Test
    void ac_t01_10_providerFailureAfterFirstTokenIsAnSseErrorEventAndTheStreamEnds() throws Exception {
        stubClaude(ClaudeSse.start(MODEL, 10).textBlockStart().text("partial ")
                .error("overloaded_error", "busy").body());

        HttpResponse<String> response = send(chat(body(true, "hi")).header("Authorization", "Bearer " + KEY).build());

        assertThat(response.statusCode()).isEqualTo(200);
        List<JsonNode> events = dataEvents(response.body());
        assertThat(events.get(0).path("choices").get(0).path("delta").path("content").asText()).isEqualTo("partial ");
        JsonNode last = events.get(events.size() - 1);
        assertThat(last.path("error").path("code").asText()).isEqualTo("provider_error");
        assertThat(response.body()).doesNotContain("[DONE]");
        Map<String, Object> row = awaitSingleLogRow();
        assertThat(row).containsEntry("status", "error").containsEntry("error_code", "provider_error");
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(200);
    }

    @Test
    void upstreamRejectionIsA400AndNotRetried() throws Exception {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}")));

        HttpResponse<String> response = send(chat(body(false, "hi")).header("Authorization", "Bearer " + KEY).build());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asText()).isEqualTo("upstream_rejected");
        CLAUDE.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void claudeReceivesTheApiKeyHeaderAndNoSamplingParameters() throws Exception {
        stubClaude(ClaudeSse.reply(MODEL, 1, 1, "ok"));
        String json = """
                {"model":"%s","temperature":0,"top_p":0.5,"messages":[{"role":"user","content":"hi"}]}""".formatted(MODEL);

        assertThat(send(chat(json).header("Authorization", "Bearer " + KEY).build()).statusCode()).isEqualTo(200);

        var sent = CLAUDE.getAllServeEvents().get(0).getRequest();
        assertThat(sent.getHeader("x-api-key")).isEqualTo("test-key-not-secret");
        assertThat(sent.containsHeader("Authorization")).isFalse();
        assertThat(sent.getBodyAsString()).doesNotContain("temperature").doesNotContain("top_p");
    }
}
