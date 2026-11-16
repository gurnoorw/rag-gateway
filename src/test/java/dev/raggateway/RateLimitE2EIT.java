package dev.raggateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.ratelimit.FailOpenRateLimiter;
import dev.raggateway.ratelimit.RateLimiter;
import dev.raggateway.ratelimit.RedisBudgetLedger;
import dev.raggateway.support.AbstractIntegrationTest;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * t02 end to end over real HTTP, both halves merged: filter + fail-open limiter + Redis Lua bucket + Redis budget,
 * with WireMock playing Claude. AC-t02-3 (429 with {@code Retry-After}, then a successful retry) and AC-t02-6
 * (budget 429, no provider call). AC-t02-7 (Redis stopped) needs its own Redis container and context:
 * {@link RateLimitRedisDownE2EIT}.
 *
 * <p>{@code gateway.rate-limit.refill-period=4s}: the capacity is still the tenant's TPM (1,000), but an empty
 * bucket refills in 4 s (250 tokens/s), so the {@code Retry-After} wait is a few seconds (C24).
 */
class RateLimitE2EIT extends AbstractIntegrationTest {

    static final String MODEL = "claude-haiku-5-5";
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    static final String KEY = "rgw_test_rl_e2e_" + UUID.randomUUID().toString().replace("-", "");

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    RateLimiter limiter;

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
        registry.add("gateway.rate-limit.refill-period", () -> "4s");
    }

    @AfterAll
    static void stopWireMock() {
        CLAUDE.stop();
    }

    @BeforeEach
    void seed() {
        CLAUDE.resetAll();
        TestData.insertTenant(jdbc, "acme", 1_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY, Set.of(), false);
        // Usage above any reservation in these tests: nothing is refunded, so the bucket state is predictable.
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(MODEL, 2_000, 600, "Hel", "lo"))));
    }

    HttpResponse<String> chat(int maxTokens) throws Exception {
        String body = """
                {"model":"%s","max_tokens":%d,"messages":[{"role":"user","content":"hello there"}]}"""
                .formatted(MODEL, maxTokens);
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                        .header("Authorization", "Bearer " + KEY).header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theApplicationUsesTheFailOpenLimiter() {
        assertThat(limiter).isInstanceOf(FailOpenRateLimiter.class);
    }

    @Test
    void ac_t02_3_rateLimited429HasRetryAfterAndARetryAfterThatWaitSucceeds() throws Exception {
        // Reservation = 8 (input estimate of "hello there") + 992 = the whole 1,000-token bucket; usage is above
        // it, so nothing comes back. The next 608-token request needs about 2.4 s of refill.
        assertThat(chat(992).statusCode()).isEqualTo(200);

        HttpResponse<String> limited = chat(600);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(limited.body()).at("/error/code").asText()).isEqualTo("rate_limited");
        String retryAfter = limited.headers().firstValue("Retry-After").orElseThrow();
        long seconds = Long.parseLong(retryAfter);
        assertThat(seconds).isGreaterThanOrEqualTo(1);
        assertThat(CLAUDE.getAllServeEvents()).hasSize(1); // the 429 never reached the provider

        Thread.sleep(Duration.ofSeconds(seconds)); // the behaviour under test is this wait
        HttpResponse<String> retry = chat(600);

        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(CLAUDE.getAllServeEvents()).hasSize(2);
    }

    @Test
    void ac_t02_6_overBudgetIs429BudgetExceededWithNoProviderCall() throws Exception {
        String budgetKey = RedisBudgetLedger.key("acme", YearMonth.now(ZoneOffset.UTC));
        redis.opsForValue().set(budgetKey, "10000000"); // $10.00 spent of a $10.00 budget

        HttpResponse<String> response = chat(50);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(response.body()).at("/error/code").asText()).isEqualTo("budget_exceeded");
        assertThat(JSON.readTree(response.body()).at("/error/type").asText()).isEqualTo("insufficient_quota");
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        assertThat(CLAUDE.getAllServeEvents()).isEmpty();
        assertThat(redis.opsForValue().get(budgetKey)).isEqualTo("10000000");
        // the bucket was never touched
        assertThat(redis.hasKey("rl:{acme}")).isFalse();
        // and the rejection has its log row
        // Scoped to this request's id: a slow row from an earlier test may land after the cleanup (TEAM_RULES).
        UUID requestId = UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow());
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql("SELECT count(*) FROM request_log WHERE request_id = ?")
                .param(requestId).query(Long.class).single() == 1);
        Map<String, Object> row = jdbc.sql("SELECT * FROM request_log WHERE request_id = ?").param(requestId)
                .query().singleRow();
        assertThat(row).containsEntry("error_code", "budget_exceeded").containsEntry("input_tokens", 0);
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(429);
    }
}
