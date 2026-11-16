package dev.raggateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.routing.RuleBasedModelSelector;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.PrometheusScrape;
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
 * t06 end to end over real HTTP with the starting rules from application.yml ({@code short} → Haiku, default Opus),
 * WireMock playing Claude. Shows which model the provider was really asked for (the upstream body), the
 * {@code X-Gateway-Route-Rule} header, the log row (selected by this test's own {@code X-Request-Id}) and the
 * {@code gateway.routing.decisions} counter. AC-t06-1, -2, -3, -4, -7, -8 ({@link RuleBasedModelSelector} unit tests
 * cover -5 and -6).
 */
class ModelSelectionE2EIT extends AbstractIntegrationTest {

    static final String OPUS = "claude-opus-5-5";
    static final String SONNET = "claude-sonnet-5-5";
    static final String HAIKU = "claude-haiku-5-5";
    static final WireMockServer CLAUDE = startWireMock();
    // Obviously fake gateway keys, created for this test only.
    static final String KEY = "rgw_test_routing_all_" + UUID.randomUUID().toString().replace("-", "");
    static final String KEY_HAIKU_ONLY = "rgw_test_routing_haiku_" + UUID.randomUUID().toString().replace("-", "");
    static final String KEY_OPUS_ONLY = "rgw_test_routing_opus_" + UUID.randomUUID().toString().replace("-", "");
    /** About 2,334 estimated tokens at 3 characters per token (D-11): above the {@code short} rule's 2,000. */
    static final String LONG_PROMPT = "word ".repeat(1_400).strip();

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        CLAUDE.stop();
    }

    @BeforeEach
    void seed() {
        CLAUDE.resetAll();
        for (String model : List.of(OPUS, SONNET, HAIKU)) {
            CLAUDE.stubFor(post("/v1/messages").withRequestBody(matchingJsonPath("$.model", equalTo(model)))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
                            .withBody(ClaudeSse.reply(model, 12, 2, "Hel", "lo"))));
        }
        TestData.insertTenant(jdbc, "acme", 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY, Set.of(), false);
        TestData.insertTenant(jdbc, "haiku-only", 1_000_000, BigDecimal.TEN, Set.of(HAIKU));
        TestData.insertKey(jdbc, "haiku-only", KEY_HAIKU_ONLY, Set.of(), false);
        TestData.insertTenant(jdbc, "opus-only", 1_000_000, BigDecimal.TEN, Set.of(OPUS));
        TestData.insertKey(jdbc, "opus-only", KEY_OPUS_ONLY, Set.of(), false);
    }

    // ---- helpers ----

    static String body(String model, boolean stream, int maxTokens, String prompt) {
        return """
                {"model":"%s","stream":%s,"temperature":0,"max_tokens":%d,"messages":[{"role":"user","content":"%s"}]}"""
                .formatted(model, stream, maxTokens, prompt);
    }

    HttpResponse<String> chat(String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    int upstreamCalls(String model) {
        return CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.model", equalTo(model)))).size();
    }

    int upstreamCalls() {
        return CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages"))).size();
    }

    static String header(HttpResponse<String> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    Map<String, Object> logRow(HttpResponse<String> r) {
        UUID id = UUID.fromString(header(r, GatewayHeaders.REQUEST_ID));
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                "SELECT count(*) FROM request_log WHERE request_id = ?").param(id).query(Long.class).single() == 1);
        return jdbc.sql("SELECT * FROM request_log WHERE request_id = ?").param(id).query().singleRow();
    }

    double decisions(String rule, String model) {
        return PrometheusScrape.fetch(baseUrl())
                .sum("gateway_routing_decisions_total", Map.of("rule", rule, "model", model));
    }

    // ---- AC-t06-1, AC-t06-7 ----

    @Test
    void ac_t06_1_and_7_autoWithAShortPromptCallsHaikuAndSaysWhichRule() throws Exception {
        double before = decisions("short", HAIKU);

        HttpResponse<String> r = chat(KEY, body("auto", false, 64, "hello " + UUID.randomUUID()));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstreamCalls(HAIKU)).isEqualTo(1);
        assertThat(upstreamCalls()).isEqualTo(1);
        assertThat(header(r, GatewayHeaders.ROUTE_RULE)).isEqualTo("short");
        assertThat(header(r, GatewayHeaders.MODEL)).isEqualTo(HAIKU);
        assertThat(logRow(r)).containsEntry("requested_model", "auto").containsEntry("model_used", HAIKU)
                .containsEntry("route_rule", "short");
        assertThat(decisions("short", HAIKU)).isEqualTo(before + 1);
    }

    // ---- AC-t06-2, AC-t06-7 ----

    @Test
    void ac_t06_2_and_7_autoWithALongPromptCallsOpusOnTheDefault() throws Exception {
        double before = decisions("default", OPUS);

        HttpResponse<String> r = chat(KEY, body("auto", true, 64, LONG_PROMPT));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("data: [DONE]");
        assertThat(upstreamCalls(OPUS)).isEqualTo(1);
        assertThat(upstreamCalls()).isEqualTo(1);
        assertThat(header(r, GatewayHeaders.ROUTE_RULE)).isEqualTo("default");
        assertThat(header(r, GatewayHeaders.MODEL)).isEqualTo(OPUS);
        assertThat(logRow(r)).containsEntry("requested_model", "auto").containsEntry("model_used", OPUS)
                .containsEntry("route_rule", "default");
        assertThat(decisions("default", OPUS)).isEqualTo(before + 1);
    }

    @Test
    void autoSkipsTheShortRuleForATenantWithoutHaiku() throws Exception {
        HttpResponse<String> r = chat(KEY_OPUS_ONLY, body("auto", false, 64, "hello " + UUID.randomUUID()));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstreamCalls(OPUS)).isEqualTo(1);
        assertThat(header(r, GatewayHeaders.ROUTE_RULE)).isEqualTo("default");
    }

    // ---- AC-t06-3 ----

    @Test
    void ac_t06_3_anExplicitModelIsPassedThroughWithoutRule() throws Exception {
        double before = decisions("explicit", SONNET);

        // A short prompt that auto would send to Haiku: the explicit name wins.
        HttpResponse<String> r = chat(KEY, body(SONNET, false, 64, "hello " + UUID.randomUUID()));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstreamCalls(SONNET)).isEqualTo(1);
        assertThat(upstreamCalls()).isEqualTo(1);
        assertThat(header(r, GatewayHeaders.ROUTE_RULE)).isNull();
        assertThat(header(r, GatewayHeaders.MODEL)).isEqualTo(SONNET);
        Map<String, Object> row = logRow(r);
        assertThat(row).containsEntry("requested_model", SONNET).containsEntry("model_used", SONNET);
        assertThat(row.get("route_rule")).isNull();
        assertThat(decisions("explicit", SONNET)).isEqualTo(before + 1);
    }

    // ---- AC-t06-4 ----

    @Test
    void ac_t06_4_aModelOutsideTheTenantsListIs403WithNoProviderCall() throws Exception {
        HttpResponse<String> explicit = chat(KEY_HAIKU_ONLY, body(OPUS, false, 64, "hello"));
        // auto: the short rule does not match a long prompt and the default (Opus) is not allowed either.
        HttpResponse<String> auto = chat(KEY_HAIKU_ONLY, body("auto", false, 64, LONG_PROMPT));

        for (HttpResponse<String> r : List.of(explicit, auto)) {
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).contains("\"code\":\"model_not_allowed\"");
            assertThat(logRow(r)).containsEntry("error_code", "model_not_allowed");
        }
        assertThat(upstreamCalls()).isZero();
    }

    @Test
    void anUnknownModelIs400WithNoProviderCall() throws Exception {
        HttpResponse<String> r = chat(KEY, body("claude-unknown-model", false, 64, "hello"));

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"invalid_request\"");
        assertThat(upstreamCalls()).isZero();
    }

    // ---- AC-t06-8 ----

    @Test
    void ac_t06_8_theCacheKeyHoldsTheResolvedModelNotAuto() throws Exception {
        String prompt = "cache me " + UUID.randomUUID();

        HttpResponse<String> autoHaiku = chat(KEY, body("auto", false, 64, prompt));
        logRow(autoHaiku);
        // Identical request except for the model: Opus must not be served Haiku's answer.
        HttpResponse<String> opus = chat(KEY, body(OPUS, false, 64, prompt));
        logRow(opus);
        // Identical to the first request with the model it resolved to: the same entry.
        HttpResponse<String> explicitHaiku = chat(KEY, body(HAIKU, false, 64, prompt));

        assertThat(header(autoHaiku, GatewayHeaders.MODEL)).isEqualTo(HAIKU);
        assertThat(header(autoHaiku, GatewayHeaders.CACHE)).isEqualTo("MISS");
        assertThat(header(opus, GatewayHeaders.CACHE)).isEqualTo("MISS");
        assertThat(header(opus, GatewayHeaders.MODEL)).isEqualTo(OPUS);
        assertThat(header(explicitHaiku, GatewayHeaders.CACHE)).isEqualTo("HIT");
        assertThat(header(explicitHaiku, GatewayHeaders.MODEL)).isEqualTo(HAIKU);
        assertThat(upstreamCalls(HAIKU)).isEqualTo(1);
        assertThat(upstreamCalls(OPUS)).isEqualTo(1);
        assertThat(redis.keys("cache:v1:*")).hasSize(2);
    }
}
