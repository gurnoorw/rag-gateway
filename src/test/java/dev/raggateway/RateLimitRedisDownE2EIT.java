package dev.raggateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.ratelimit.RedisTokenBucketLimiter;
import dev.raggateway.support.SharedContainers;
import dev.raggateway.support.TestData;
import dev.raggateway.support.TestImages;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t02-7 end to end: with Redis stopped, requests are still served, the per-instance bucket still rejects
 * above the tenant's limit, and {@code gateway.ratelimit.failopen} rises (D-17). The application gets its own
 * Redis container (stopping the shared one would break every other test) and therefore its own context, which
 * is closed after this class. It does not extend {@code AbstractIntegrationTest}: that class registers the shared
 * Redis, and its registration would win over this one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class RateLimitRedisDownE2EIT {

    static final String MODEL = "claude-haiku-5-5";
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    static final GenericContainer<?> OWN_REDIS = startRedis();
    static final String KEY = "rgw_test_rl_down_" + UUID.randomUUID().toString().replace("-", "");

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcClient jdbc;

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    static GenericContainer<?> startRedis() {
        var container = new GenericContainer<>(TestImages.REDIS).withExposedPorts(6379);
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
        var pg = SharedContainers.postgres();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.data.redis.host", OWN_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> OWN_REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2s"); // cold container; the down path is REJECT_COMMANDS
    }

    @AfterAll
    static void stopAll() {
        CLAUDE.stop();
        OWN_REDIS.stop();
    }

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE request_log, api_keys, tenants RESTART IDENTITY CASCADE").update();
        CLAUDE.resetAll();
        TestData.insertTenant(jdbc, "acme", 1_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY, Set.of(), false);
        // Usage above the reservation: nothing is refunded, so each request takes its full 608 tokens.
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(MODEL, 2_000, 600, "Hel", "lo"))));
    }

    HttpResponse<String> chat() throws Exception {
        // Reservation = 8 (input estimate of "hello there") + 600 = 608 of a 1,000-token bucket.
        String body = """
                {"model":"%s","max_tokens":600,"messages":[{"role":"user","content":"hello there"}]}""".formatted(MODEL);
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                        .header("Authorization", "Bearer " + KEY).header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Fail-open decisions: a failed Redis call, or a call skipped inside the D-46 skip window. */
    double failOpen(String component) {
        var failed = registry.find(MetricNames.LIMIT_FAIL_OPEN).tag("component", component).counter();
        var skipped = registry.find(MetricNames.REDIS_SKIPPED).tag("component", component).counter();
        return (failed == null ? 0 : failed.count()) + (skipped == null ? 0 : skipped.count());
    }

    @Test
    void ac_t02_7_redisStoppedRequestsStillServedLocalLimitHoldsAndFailOpenRises() throws Exception {
        // Redis up: the shared bucket is used.
        HttpResponse<String> before = chat();
        assertThat(before.statusCode()).as(before.body()).isEqualTo(200);
        // the application's Redis is this class's own container: the bucket is there
        assertThat(OWN_REDIS.execInContainer("redis-cli", "EXISTS", RedisTokenBucketLimiter.redisKey("acme"))
                .getStdout().trim()).isEqualTo("1");
        double limiterFailOpenBefore = failOpen("limiter");
        double budgetFailOpenBefore = failOpen("budget");

        OWN_REDIS.stop();

        // Redis down: the per-instance bucket (fresh, 1,000 tokens) admits one 608-token request ...
        HttpResponse<String> served = chat();
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        // ... and still rejects the next one: the limit holds per instance.
        HttpResponse<String> limited = chat();
        assertThat(limited.statusCode()).as(limited.body()).isEqualTo(429);
        assertThat(JSON.readTree(limited.body()).at("/error/code").asText()).isEqualTo("rate_limited");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isGreaterThanOrEqualTo(1);

        assertThat(CLAUDE.getAllServeEvents()).hasSize(2);
        assertThat(failOpen("limiter")).isGreaterThanOrEqualTo(limiterFailOpenBefore + 2);
        assertThat(failOpen("budget")).isGreaterThan(budgetFailOpenBefore); // budget check skipped, add dropped
    }
}
