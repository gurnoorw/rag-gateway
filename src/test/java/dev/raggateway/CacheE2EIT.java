package dev.raggateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.cache.CacheFilter;
import dev.raggateway.cache.RedisResponseCache;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.GatewayPipeline;
import dev.raggateway.pipeline.RequestValidationFilter;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.ratelimit.RateLimitFilter;
import dev.raggateway.ratelimit.RedisTokenBucketLimiter;
import dev.raggateway.routing.ModelSelectionFilter;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.OllamaNdjson;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * t05 end to end, both halves merged: {@link CacheFilter} with {@link RedisResponseCache} and the canonical key on the
 * shared Redis, over real HTTP, WireMock playing Claude (and Ollama as the fallback). AC-t05-1, -2, -5, -6, -7, -9,
 * -10 and AC-t02-8. Log rows are selected by this test's own {@code X-Request-Id}s.
 */
class CacheE2EIT extends AbstractIntegrationTest {

    static final String MODEL = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    static final WireMockServer OLLAMA = startWireMock();
    // Obviously fake gateway keys, created for this test only.
    static final String KEY_A = "rgw_test_cache_a_" + UUID.randomUUID().toString().replace("-", "");
    static final String KEY_B = "rgw_test_cache_b_" + UUID.randomUUID().toString().replace("-", "");

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    GatewayPipeline pipeline;

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort().containerThreads(80));
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
        TestData.insertTenant(jdbc, "acme", 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY_A, Set.of(), false);
        TestData.insertTenant(jdbc, "globex", 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "globex", KEY_B, Set.of(), false);
    }

    void claudeAnswers(String... pieces) {
        claudeAnswers(0, pieces);
    }

    void claudeAnswers(int delayMs, String... pieces) {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200).withFixedDelay(delayMs)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(MODEL, 12, pieces.length, pieces))));
    }

    void claudeFails() {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"fake 500\"}}")));
    }

    int claudeCalls() {
        return CLAUDE.findAll(postRequestedFor(urlEqualTo("/v1/messages"))).size();
    }

    static String body(boolean stream, double temperature, String prompt) {
        return """
                {"model":"%s","stream":%s,"temperature":%s,"max_tokens":64,"messages":[{"role":"user","content":"%s"}]}"""
                .formatted(MODEL, stream, temperature, prompt);
    }

    HttpRequest request(String key, String body) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    HttpResponse<String> chat(String key, String body) throws Exception {
        return http.send(request(key, body), HttpResponse.BodyHandlers.ofString());
    }

    static String cacheHeader(HttpResponse<String> r) {
        return r.headers().firstValue(GatewayHeaders.CACHE).orElseThrow();
    }

    static String content(HttpResponse<String> json) throws Exception {
        return JSON.readTree(json.body()).at("/choices/0/message/content").asText();
    }

    static UUID requestId(HttpResponse<String> r) {
        return UUID.fromString(r.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow());
    }

    Map<String, Object> logRow(HttpResponse<String> r) {
        UUID id = requestId(r);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                "SELECT count(*) FROM request_log WHERE request_id = ?").param(id).query(Long.class).single() == 1);
        return jdbc.sql("SELECT * FROM request_log WHERE request_id = ?").param(id).query().singleRow();
    }

    /**
     * Wait until the requests have fully finished on the server. The client has its response once the stream is
     * closed; the store and the unlock run after that, and the log row is written after both, so a row means the
     * cache state is final.
     */
    @SafeVarargs
    final void finished(HttpResponse<String>... responses) {
        for (HttpResponse<String> r : responses) logRow(r);
    }

    Set<String> cacheEntries() {
        return redis.keys("cache:v1:*");
    }

    Set<String> cacheLocks() {
        return redis.keys(RedisResponseCache.LOCK_PREFIX + "*");
    }

    // ---- wiring ----

    @Test
    void theFilterRunsBetweenModelSelectionAndTheRateLimiter() {
        List<Class<?>> order = new ArrayList<>();
        for (GatewayFilter f : ((DefaultGatewayPipeline) pipeline).filters()) order.add(f.getClass());

        assertThat(order).containsExactly(RequestValidationFilter.class, ModelSelectionFilter.class,
                CacheFilter.class, RateLimitFilter.class);
    }

    // ---- AC-t05-1, AC-t05-9 ----

    @Test
    void ac_t05_1_twoIdenticalTemperatureZeroRequestsMakeOneProviderCall() throws Exception {
        claudeAnswers("Hel", "lo");

        HttpResponse<String> first = chat(KEY_A, body(false, 0, "hello"));
        HttpResponse<String> second = chat(KEY_A, body(false, 0, "hello"));

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(cacheHeader(first)).isEqualTo("MISS");
        assertThat(cacheHeader(second)).isEqualTo("HIT");
        assertThat(second.headers().firstValue(GatewayHeaders.PROVIDER)).hasValue("cache");
        assertThat(content(second)).isEqualTo(content(first)).isEqualTo("Hello");
        finished(first, second);
        assertThat(claudeCalls()).isEqualTo(1);
        assertThat(cacheEntries()).hasSize(1);
        assertThat(cacheLocks()).as("the fill lock was released").isEmpty();
    }

    @Test
    void ac_t05_9_aHitReplaysAsSseEndingInDoneAndAsJson() throws Exception {
        claudeAnswers("cached ", "answer");
        assertThat(cacheHeader(chat(KEY_A, body(true, 0, "replay me")))).isEqualTo("MISS");

        HttpResponse<String> sse = chat(KEY_A, body(true, 0, "replay me"));
        HttpResponse<String> json = chat(KEY_A, body(false, 0, "replay me"));

        assertThat(claudeCalls()).as("stream is not in the key").isEqualTo(1);
        assertThat(cacheHeader(sse)).isEqualTo("HIT");
        assertThat(sse.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        List<String> data = sse.body().lines().filter(l -> l.startsWith("data: ")).map(l -> l.substring(6)).toList();
        assertThat(data).last().isEqualTo("[DONE]");
        StringBuilder text = new StringBuilder();
        String finish = null;
        for (String d : data.subList(0, data.size() - 1)) {
            JsonNode chunk = JSON.readTree(d);
            assertThat(chunk.path("object").asText()).isEqualTo("chat.completion.chunk");
            JsonNode choice = chunk.at("/choices/0");
            if (choice.isMissingNode()) continue;
            text.append(choice.at("/delta/content").asText(""));
            if (!choice.path("finish_reason").isNull() && !choice.path("finish_reason").isMissingNode()) {
                finish = choice.path("finish_reason").asText();
            }
        }
        assertThat(text.toString()).isEqualTo("cached answer");
        assertThat(finish).isEqualTo("stop");

        assertThat(cacheHeader(json)).isEqualTo("HIT");
        assertThat(json.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode body = JSON.readTree(json.body());
        assertThat(body.path("object").asText()).isEqualTo("chat.completion");
        assertThat(body.at("/choices/0/message/content").asText()).isEqualTo("cached answer");
        assertThat(body.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(body.at("/usage/total_tokens").asInt()).isZero();
    }

    // ---- AC-t05-2 ----

    @Test
    void ac_t05_2_theSameRequestFromAnotherTenantCallsTheProviderAgain() throws Exception {
        claudeAnswers("hi");

        HttpResponse<String> a = chat(KEY_A, body(false, 0, "hello"));
        HttpResponse<String> b = chat(KEY_B, body(false, 0, "hello"));

        assertThat(cacheHeader(a)).isEqualTo("MISS");
        assertThat(cacheHeader(b)).isEqualTo("MISS");
        assertThat(claudeCalls()).isEqualTo(2);
        finished(a, b);
        assertThat(cacheEntries()).hasSize(2);
    }

    // ---- AC-t05-4 over HTTP (also unit-tested) ----

    @Test
    void ac_t05_4_temperatureAboveZeroTwiceMakesTwoProviderCalls() throws Exception {
        claudeAnswers("hi");

        HttpResponse<String> first = chat(KEY_A, body(false, 0.7, "hello"));
        HttpResponse<String> second = chat(KEY_A, body(false, 0.7, "hello"));

        assertThat(cacheHeader(first)).isEqualTo("NOT_ELIGIBLE");
        assertThat(cacheHeader(second)).isEqualTo("NOT_ELIGIBLE");
        assertThat(claudeCalls()).isEqualTo(2);
        finished(first, second);
        assertThat(cacheEntries()).isEmpty();
    }

    // ---- AC-t05-5, AC-t05-6 ----

    @Test
    void ac_t05_5_aFailedProviderCallLeavesNoKeyInRedis() throws Exception {
        claudeFails();
        OLLAMA.stubFor(post("/api/chat").willReturn(aResponse().withStatus(500).withBody("{\"error\":\"fake\"}")));

        HttpResponse<String> r = chat(KEY_A, body(false, 0, "hello"));

        assertThat(r.statusCode()).isEqualTo(503);
        // An error before the first byte is a plain JSON error without the served-by headers; the row has the status.
        assertThat(logRow(r)).containsEntry("cache_status", "miss").containsEntry("status", "error");
        assertThat(cacheEntries()).isEmpty();
        assertThat(cacheLocks()).as("the fill lock was released").isEmpty();
    }

    @Test
    void ac_t05_6_aResponseServedByTheFallbackLeavesNoKeyInRedis() throws Exception {
        claudeFails();
        OLLAMA.stubFor(post("/api/chat").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", OllamaNdjson.CONTENT_TYPE)
                .withBody(OllamaNdjson.reply(LLAMA, 12, 2, "from ", "llama"))));

        HttpResponse<String> r = chat(KEY_A, body(false, 0, "hello"));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue(GatewayHeaders.PROVIDER)).hasValue("ollama");
        assertThat(content(r)).isEqualTo("from llama");
        assertThat(logRow(r)).containsEntry("fallback_used", true).containsEntry("cache_status", "miss");
        assertThat(cacheEntries()).isEmpty();
        assertThat(cacheLocks()).as("the fill lock was released").isEmpty();
    }

    // ---- AC-t05-7 ----

    @Test
    void ac_t05_7_fiftyConcurrentIdenticalRequestsOnAColdCacheMakeOneProviderCall() throws Exception {
        int n = 50;
        // The provider answers after 1 s, so every request arrives while the first one still holds the lock.
        claudeAnswers(1_000, "one ", "answer");
        String body = body(false, 0, "stampede");

        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(http.sendAsync(request(KEY_A, body), HttpResponse.BodyHandlers.ofString()));
        }
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (var f : futures) responses.add(f.get());

        assertThat(claudeCalls()).isEqualTo(1);
        for (HttpResponse<String> r : responses) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(content(r)).isEqualTo("one answer");
        }
        assertThat(responses).filteredOn(r -> cacheHeader(r).equals("MISS")).hasSize(1);
        assertThat(responses).filteredOn(r -> cacheHeader(r).equals("HIT")).hasSize(n - 1);
        for (HttpResponse<String> r : responses) finished(r);
        assertThat(cacheEntries()).hasSize(1);
        assertThat(cacheLocks()).isEmpty();
    }

    // ---- AC-t05-10, AC-t02-8 ----

    @Test
    void ac_t05_10_aHitLeavesTheBucketUnchangedAndLogsCostZero() throws Exception {
        claudeAnswers("Hel", "lo");
        HttpResponse<String> miss = chat(KEY_A, body(false, 0, "hello"));
        String bucket = RedisTokenBucketLimiter.redisKey("acme");
        await().atMost(Duration.ofSeconds(10)).until(() -> redis.hasKey(bucket));
        logRow(miss); // the miss has settled (refund and budget add run before the log row)
        Map<Object, Object> before = redis.opsForHash().entries(bucket);

        HttpResponse<String> hit = chat(KEY_A, body(false, 0, "hello"));

        assertThat(cacheHeader(hit)).isEqualTo("HIT");
        assertThat(redis.opsForHash().entries(bucket)).as("tokens and refill timestamp untouched").isEqualTo(before);
        Map<String, Object> row = logRow(hit);
        assertThat(row).containsEntry("cache_status", "hit").containsEntry("provider_used", "cache")
                .containsEntry("status", "ok");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(((Number) row.get("input_tokens")).intValue()).isZero();
        assertThat(((Number) row.get("output_tokens")).intValue()).isZero();
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theBypassHeaderCallsTheProviderAndStoresNothing() throws Exception {
        claudeAnswers("hi");
        HttpRequest bypass = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + KEY_A).header("Content-Type", "application/json")
                .header(GatewayHeaders.CACHE_BYPASS, "true").timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body(false, 0, "hello"))).build();

        HttpResponse<String> r = http.send(bypass, HttpResponse.BodyHandlers.ofString());

        assertThat(cacheHeader(r)).isEqualTo("BYPASS");
        assertThat(logRow(r)).containsEntry("cache_status", "bypass");
        assertThat(cacheEntries()).isEmpty();
    }
}
