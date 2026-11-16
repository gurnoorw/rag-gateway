package dev.raggateway.metrics;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.OllamaNdjson;
import dev.raggateway.support.PrometheusScrape;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AC-t04-3: after mixed traffic (success, stream, unknown model, upstream 400, fallback, exhausted chain, budget 429,
 * bad key, a client-chosen {@code X-Request-Id}, a JSON-schema request answered validly and one answered invalidly
 * twice), no {@code gateway_*} or {@code rag_*} series on
 * {@code /actuator/prometheus} has a label outside {@link MetricNames#ALLOWED_LABELS} (plus {@code le}) or a value
 * outside its bounded set, and no value contains a request id, an API key, prompt text or the raw model string.
 * {@link MetricLabelPolicyTest} shows each rule fails on a scrape that breaks it.
 */
class MetricLabelPolicyIT extends AbstractIntegrationTest {

    static final String HAIKU = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final WireMockServer CLAUDE = startWireMock();
    static final WireMockServer OLLAMA = startWireMock();

    @Autowired
    GatewayProperties properties;

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    /** Everything taken from a request that must never reach a label value. */
    final Set<String> requestData = new HashSet<>();
    final String suffix = UUID.randomUUID().toString().substring(0, 8);
    final String paying = "policy-a-" + suffix;
    final String suspended = "policy-b-" + suffix;
    // Obviously fake gateway keys, created for this test only.
    final String payingKey = "rgw_test_policy_key_a_" + UUID.randomUUID().toString().replace("-", "");
    final String suspendedKey = "rgw_test_policy_key_b_" + UUID.randomUUID().toString().replace("-", "");
    int pipelineRequests;

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void providersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
        registry.add("gateway.providers.ollama.base-url", OLLAMA::baseUrl);
        registry.add("gateway.fallback.backoff-base", () -> "10ms");
        registry.add("gateway.fallback.backoff-cap", () -> "20ms");
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
        TestData.insertTenant(jdbc, paying, 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, paying, payingKey, Set.of(), false);
        TestData.insertTenant(jdbc, suspended, 1_000_000, BigDecimal.ZERO, Set.of()); // budget 0: every request 429
        TestData.insertKey(jdbc, suspended, suspendedKey, Set.of(), false);
        requestData.addAll(Set.of(payingKey, suspendedKey));
    }

    HttpResponse<String> chat(String key, String model, boolean stream, String prompt, String clientRequestId)
            throws Exception {
        if (!properties.models().containsKey(model)) requestData.add(model);
        requestData.add(prompt);
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"model":"%s","stream":%s,"max_tokens":64,"messages":[{"role":"user","content":"%s"}]}"""
                        .formatted(model, stream, prompt)));
        if (clientRequestId != null) {
            builder.header(GatewayHeaders.REQUEST_ID, clientRequestId);
            requestData.add(clientRequestId);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue(GatewayHeaders.REQUEST_ID).ifPresent(requestData::add);
        if (response.statusCode() != 401) pipelineRequests++;
        return response;
    }

    /** A {@code json_schema} request (t07); the schema asks for a string {@code answer} and nothing else. */
    HttpResponse<String> chatWithSchema(String key, String model, String prompt) throws Exception {
        requestData.add(prompt);
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"model":"%s","max_tokens":64,"messages":[{"role":"user","content":"%s"}],
                         "response_format":{"type":"json_schema","json_schema":{"name":"answer","schema":
                           {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],
                            "additionalProperties":false}}}}""".formatted(model, prompt)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue(GatewayHeaders.REQUEST_ID).ifPresent(requestData::add);
        pipelineRequests++;
        return response;
    }

    String prompt() {
        return "free text prompt " + UUID.randomUUID();
    }

    static void claude(ResponseDefinitionBuilder response) {
        CLAUDE.resetAll();
        CLAUDE.stubFor(post("/v1/messages").willReturn(response));
    }

    static ResponseDefinitionBuilder claudeError(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"fake " + status + "\"}}");
    }

    double requestsOfOwnTenants() {
        PrometheusScrape scrape = PrometheusScrape.fetch(baseUrl());
        return scrape.sum("gateway_requests_total", Map.of("tenant", paying))
                + scrape.sum("gateway_requests_total", Map.of("tenant", suspended));
    }

    @Test
    void ac_t04_3_noMetricLabelCarriesARequestIdAKeyOrFreeText() throws Exception {
        claude(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(HAIKU, 8, 3, "ok")));
        assertThat(chat(payingKey, HAIKU, true, prompt(), null).statusCode()).isEqualTo(200);
        assertThat(chat(payingKey, HAIKU, false, prompt(), "client-chosen-" + UUID.randomUUID()).statusCode())
                .isEqualTo(200);
        String rawModel = "client-model-" + UUID.randomUUID();
        assertThat(chat(payingKey, rawModel, false, prompt(), null).statusCode()).isEqualTo(400);
        assertThat(chat(suspendedKey, HAIKU, false, prompt(), null).statusCode()).isEqualTo(429);
        String badKey = "rgw_test_policy_unknown_key_" + UUID.randomUUID().toString().replace("-", "");
        requestData.add(badKey);
        assertThat(chat(badKey, HAIKU, false, prompt(), null).statusCode()).isEqualTo(401);

        claude(claudeError(400));
        assertThat(chat(payingKey, HAIKU, false, prompt(), null).statusCode()).isEqualTo(400);

        claude(claudeError(500));
        OLLAMA.stubFor(post("/api/chat").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", OllamaNdjson.CONTENT_TYPE).withBody(OllamaNdjson.reply(LLAMA, 4, 1, "y"))));
        assertThat(chat(payingKey, HAIKU, true, prompt(), null).statusCode()).isEqualTo(200);

        OLLAMA.resetAll();
        OLLAMA.stubFor(post("/api/chat").willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json").withBody(OllamaNdjson.errorBody("fake overload"))));
        assertThat(chat(payingKey, HAIKU, false, prompt(), null).statusCode()).isEqualTo(503);

        // t07: a schema request answered with valid JSON, then one answered with invalid JSON twice (502). The output
        // text is request data too: it must not reach a label.
        String validOutput = "{\"answer\":\"valid-" + suffix + "\"}";
        String invalidOutput = "{\"answer\":42,\"invalid-" + suffix + "\":true}";
        requestData.addAll(Set.of("valid-" + suffix, "invalid-" + suffix));
        claude(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(HAIKU, 8, 5, validOutput)));
        assertThat(chatWithSchema(payingKey, HAIKU, prompt()).statusCode()).isEqualTo(200);
        claude(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(HAIKU, 8, 5, invalidOutput)));
        assertThat(chatWithSchema(payingKey, HAIKU, prompt()).statusCode()).isEqualTo(502);

        await().atMost(Duration.ofSeconds(5)).until(() -> requestsOfOwnTenants() == pipelineRequests);
        PrometheusScrape scrape = PrometheusScrape.fetch(baseUrl());

        // The traffic produced the label values the policy has to allow, so the check below is not vacuous.
        assertThat(scrape.sum("gateway_requests_total", Map.of("tenant", paying, "model", "other", "provider", "none",
                "code", "invalid_request"))).isEqualTo(1.0);
        assertThat(scrape.sum("gateway_requests_total", Map.of("tenant", suspended, "code", "budget_exceeded",
                "status_class", "4xx"))).isEqualTo(1.0);
        assertThat(scrape.sum("gateway_requests_total", Map.of("tenant", paying, "provider", "ollama",
                "status_class", "2xx"))).isEqualTo(1.0);
        // An exhausted chain is labelled with its last attempt's provider, as the log row's provider_used.
        assertThat(scrape.sum("gateway_requests_total", Map.of("tenant", paying, "provider", "ollama",
                "status_class", "5xx", "code", "all_providers_failed"))).isEqualTo(1.0);
        assertThat(scrape.sum("gateway_ratelimit_rejections_total", Map.of("tenant", suspended))).isEqualTo(1.0);
        assertThat(scrape.sum("gateway_validation_failures_total", Map.of("model", HAIKU, "kind", "schema")))
                .isEqualTo(2.0);

        assertThat(scrape.labelNames(MetricLabelPolicy::inScope))
                .isSubsetOf(union(MetricNames.ALLOWED_LABELS, Set.of(MetricLabelPolicy.PROMETHEUS_LE)));
        // Every tenant this application context has seen is one of this test's own (one test in this class).
        var policy = new MetricLabelPolicy(properties.models().keySet(), properties.providers().keySet(),
                Set.of(paying, suspended), requestData);
        assertThat(policy.violations(scrape)).isEmpty();
        // D-19/D-38: no latency histogram carries a tenant label, on the real scrape page.
        assertThat(scrape.labelNames(n -> n.endsWith("_seconds_bucket") && n.startsWith("gateway_")))
                .isNotEmpty().doesNotContain("tenant");
    }

    static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
