package dev.raggateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.cache.RedisResponseCache;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.PrometheusScrape;
import dev.raggateway.support.TestData;
import dev.raggateway.validation.NetworkntOutputValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * t07 end to end over real HTTP, after t06: the validating terminal over the router, the real Claude adapter against
 * WireMock, the cache on the shared Redis, the request log in Postgres. AC-t07-1, -2, -3, -4, -5, -6, -7, -8, and one
 * {@code model: "auto"} request with a schema to show routing (t06) and validation (t07) together. Log rows are
 * selected by this test's own {@code X-Request-Id}s. The validator, the structural check and the terminal's edge
 * cases are unit-tested in {@code validation/}.
 */
class ValidationE2EIT extends AbstractIntegrationTest {

    static final String HAIKU = "claude-haiku-5-5";
    static final String OPUS = "claude-opus-5-5";
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    // Obviously fake gateway key, created for this test only.
    static final String KEY = "rgw_test_validation_key_" + UUID.randomUUID().toString().replace("-", "");

    static final String SCHEMA = """
            {"type":"object","properties":{"city":{"type":"string"},"temp":{"type":"number"}},
             "required":["city","temp"],"additionalProperties":false}""";
    /** Valid against SCHEMA, sent by the mock in several pieces. */
    static final String[] VALID = {"{\"city\":", "\"Pune\",", "\"temp\":", "31.5}"};
    /** Invalid against SCHEMA, with markers that must never reach the client. */
    static final String[] INVALID = {"{\"city\":\"LEAKED-CITY\",", "\"temp\":\"LEAKED-TEMP\"}"};

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
        registry.add("gateway.fallback.backoff-base", () -> "10ms");
        registry.add("gateway.fallback.backoff-cap", () -> "20ms");
    }

    @AfterAll
    static void stopWireMock() {
        CLAUDE.stop();
    }

    @BeforeEach
    void seed() {
        CLAUDE.resetAll();
        TestData.insertTenant(jdbc, "acme", 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY, Set.of(), false);
    }

    // ---- helpers ----

    static ResponseDefinitionBuilder sse(String model, int in, int out, String... pieces) {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(model, in, out, pieces));
    }

    /** Every call gets the same answer. */
    static void claudeAlways(ResponseDefinitionBuilder answer) {
        CLAUDE.stubFor(post("/v1/messages").willReturn(answer));
    }

    /** The first call gets {@code first}, every later call {@code then}. */
    static void claudeFirstThen(ResponseDefinitionBuilder first, ResponseDefinitionBuilder then) {
        CLAUDE.stubFor(post("/v1/messages").inScenario("calls").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(first).willSetStateTo("answered once"));
        CLAUDE.stubFor(post("/v1/messages").inScenario("calls").whenScenarioStateIs("answered once")
                .willReturn(then));
    }

    static int claudeCalls() {
        return CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages"))).size();
    }

    static String body(String model, boolean stream, String schema, String prompt) {
        String format = schema == null ? "" : """
                ,"response_format":{"type":"json_schema","json_schema":{"name":"weather","schema":%s}}"""
                .formatted(schema);
        return """
                {"model":"%s","stream":%s,"temperature":0,"max_tokens":64,
                 "messages":[{"role":"user","content":"%s"}]%s}""".formatted(model, stream, prompt, format);
    }

    HttpRequest request(String body) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + KEY).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    HttpResponse<String> chat(String body) throws Exception {
        return http.send(request(body), HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> logRow(HttpResponse<?> r) {
        UUID id = UUID.fromString(r.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow());
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                "SELECT count(*) FROM request_log WHERE request_id = ?").param(id).query(Long.class).single() == 1);
        return jdbc.sql("SELECT * FROM request_log WHERE request_id = ?").param(id).query().singleRow();
    }

    double validationFailures(String model) {
        return PrometheusScrape.fetch(baseUrl())
                .sum("gateway_validation_failures_total", Map.of("model", model, "kind", "schema"));
    }

    Set<String> cacheEntries() {
        return redis.keys("cache:v1:*");
    }

    Set<String> cacheLocks() {
        return redis.keys(RedisResponseCache.LOCK_PREFIX + "*");
    }

    static String content(HttpResponse<String> json) throws Exception {
        return JSON.readTree(json.body()).at("/choices/0/message/content").asText();
    }

    /** {@code data:} payloads of an SSE body, without {@code [DONE]}, that carry content. */
    static List<String> contentChunks(String sseBody) throws Exception {
        List<String> pieces = new java.util.ArrayList<>();
        for (String line : sseBody.lines().toList()) {
            if (!line.startsWith("data: ") || line.equals("data: [DONE]")) continue;
            JsonNode delta = JSON.readTree(line.substring(6)).at("/choices/0/delta/content");
            if (delta.isTextual() && !delta.asText().isEmpty()) pieces.add(delta.asText());
        }
        return pieces;
    }

    // ---- AC-t07-1 ----

    @Test
    void ac_t07_1_validJsonFromTheModelIs200AndTheBodyValidatesAgainstTheSchema() throws Exception {
        claudeAlways(sse(HAIKU, 12, 9, VALID));

        HttpResponse<String> r = chat(body(HAIKU, false, SCHEMA, "weather in Pune"));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(new NetworkntOutputValidator(JSON).validate(SCHEMA, content(r))).isEmpty();
        assertThat(content(r)).isEqualTo(String.join("", VALID));
        assertThat(claudeCalls()).isEqualTo(1);
        assertThat(logRow(r)).containsEntry("status", "ok").containsEntry("attempts", 1);
    }

    // ---- AC-t07-2 over HTTP, with the cache ----

    @Test
    void ac_t07_2_invalidThenValidIs200AfterTwoCallsAndOnlyTheValidAnswerIsCached() throws Exception {
        claudeFirstThen(sse(HAIKU, 12, 7, INVALID), sse(HAIKU, 12, 9, VALID));
        String request = body(HAIKU, false, SCHEMA, "weather twice");

        HttpResponse<String> r = chat(request);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(content(r)).isEqualTo(String.join("", VALID));
        assertThat(claudeCalls()).isEqualTo(2);
        assertThat(logRow(r)).containsEntry("attempts", 2)
                .containsEntry("input_tokens", 24).containsEntry("output_tokens", 16);
        assertThat(cacheEntries()).hasSize(1);

        HttpResponse<String> replay = chat(request);
        assertThat(replay.headers().firstValue(GatewayHeaders.CACHE)).hasValue("HIT");
        assertThat(content(replay)).isEqualTo(String.join("", VALID));
        assertThat(claudeCalls()).isEqualTo(2);
    }

    // ---- AC-t07-3, AC-t07-7, AC-t07-8 ----

    @Test
    void ac_t07_3_7_8_invalidTwiceIs502AfterTwoCallsNothingCachedAndBothAttemptsCharged() throws Exception {
        claudeAlways(sse(HAIKU, 12, 7, INVALID));
        double failuresBefore = validationFailures(HAIKU);

        HttpResponse<String> r = chat(body(HAIKU, false, SCHEMA, "weather, badly"));

        // AC-t07-3
        assertThat(r.statusCode()).isEqualTo(502);
        JsonNode error = JSON.readTree(r.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("invalid_model_output");
        assertThat(error.path("message").asText()).hasSizeLessThanOrEqualTo(300).doesNotContain("LEAKED");
        assertThat(r.body()).doesNotContain("LEAKED");
        assertThat(claudeCalls()).isEqualTo(2);

        // AC-t07-8: the log row charges both attempts; the failure counter went up by 2.
        Map<String, Object> row = logRow(r);
        assertThat(row).containsEntry("status", "error").containsEntry("error_code", "invalid_model_output")
                .containsEntry("attempts", 2)
                .containsEntry("input_tokens", 24).containsEntry("output_tokens", 14)
                .containsEntry("usage_estimated", false);
        assertThat(((BigDecimal) row.get("cost_usd")).signum()).isPositive();
        assertThat(validationFailures(HAIKU) - failuresBefore).isEqualTo(2.0);

        // AC-t07-7: the row is written after the response is closed and the cache filter is done, so Redis is final.
        assertThat(cacheEntries()).isEmpty();
        assertThat(cacheLocks()).as("the fill lock was released").isEmpty();
    }

    // ---- AC-t07-4 ----

    @Test
    void ac_t07_4_streamWithSchemaAndInvalidOutputSendsZeroContentChunks() throws Exception {
        claudeAlways(sse(HAIKU, 12, 7, INVALID));

        HttpResponse<String> r = chat(body(HAIKU, true, SCHEMA, "weather, streamed badly"));

        // Nothing was sent before the error, so it is a real status with a JSON body (C15), not an SSE stream.
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(contentChunks(r.body())).isEmpty();
        assertThat(r.body()).doesNotContain("data:").doesNotContain("LEAKED");
        assertThat(JSON.readTree(r.body()).at("/error/code").asText()).isEqualTo("invalid_model_output");
        assertThat(claudeCalls()).isEqualTo(2);
    }

    @Test
    void streamWithSchemaAndValidOutputIsOneContentChunkThenDone() throws Exception {
        claudeAlways(sse(HAIKU, 12, 9, VALID));

        HttpResponse<String> r = chat(body(HAIKU, true, SCHEMA, "weather, streamed"));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(contentChunks(r.body())).containsExactly(String.join("", VALID));
        assertThat(r.body().strip()).endsWith("data: [DONE]");
    }

    // ---- AC-t07-5 ----

    @Test
    void ac_t07_5_withoutASchemaTokensStreamAsTheyArrive() throws Exception {
        ClaudeSse sse = ClaudeSse.start(HAIKU, 10).textBlockStart();
        for (int i = 0; i < 10; i++) sse.text("t" + i + " ");
        // WireMock sends the body in 20 pieces spread over 2 s.
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream").withBody(sse.blockStop().end("end_turn", 10).body())
                .withChunkedDribbleDelay(20, 2000)));

        HttpResponse<InputStream> response = http.send(request(body(HAIKU, true, null, "no schema")),
                HttpResponse.BodyHandlers.ofInputStream());

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
        // A buffer would deliver everything together at the end; streaming spreads it over the upstream's 2 s.
        assertThat(Duration.ofNanos(doneAt - firstContentAt)).isGreaterThan(Duration.ofMillis(800));
    }

    // ---- AC-t07-6 over HTTP ----

    @Test
    void ac_t07_6_malformedSchemaIs400WithNoProviderCall() throws Exception {
        claudeAlways(sse(HAIKU, 12, 9, VALID));

        HttpResponse<String> r = chat(body(HAIKU, false, "{\"type\":\"objekt\"}", "bad schema"));

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(r.body()).at("/error/code").asText()).isEqualTo("invalid_request");
        assertThat(claudeCalls()).isZero();
        assertThat(logRow(r)).containsEntry("attempts", 0).containsEntry("input_tokens", 0);
    }

    // ---- t06 and t07 together ----

    @Test
    void autoWithASchemaIsRoutedToTheStrongModelAndValidatedThere() throws Exception {
        // A schema never matches the "short" rule (D-42), so auto resolves to the default model.
        CLAUDE.stubFor(post("/v1/messages").withRequestBody(matchingJsonPath("$.model", equalTo(OPUS)))
                .inScenario("auto").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(OPUS, 12, 7, INVALID)).willSetStateTo("answered once"));
        CLAUDE.stubFor(post("/v1/messages").withRequestBody(matchingJsonPath("$.model", equalTo(OPUS)))
                .inScenario("auto").whenScenarioStateIs("answered once")
                .willReturn(sse(OPUS, 12, 9, VALID)));

        HttpResponse<String> r = chat(body("auto", false, SCHEMA, "short question"));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue(GatewayHeaders.ROUTE_RULE)).hasValue("default");
        assertThat(r.headers().firstValue(GatewayHeaders.MODEL)).hasValue(OPUS);
        assertThat(content(r)).isEqualTo(String.join("", VALID));
        assertThat(CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.model", equalTo(OPUS))))).hasSize(2);
        assertThat(logRow(r)).containsEntry("requested_model", "auto").containsEntry("model_used", OPUS)
                .containsEntry("route_rule", "default").containsEntry("attempts", 2);
    }
}
