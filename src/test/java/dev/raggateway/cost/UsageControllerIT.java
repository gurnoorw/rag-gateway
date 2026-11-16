package dev.raggateway.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.ratelimit.RedisBudgetLedger;
import dev.raggateway.support.AbstractIntegrationTest;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * {@code GET /v1/usage} over real HTTP against the request log in Postgres (AC-t02-9), plus the cost path end to
 * end: a request through the gateway (Claude played by WireMock) writes a log row whose cost equals the budget
 * increment in Redis (D-33). Uses this branch's pre-merge limiter bean.
 */
class UsageControllerIT extends AbstractIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    static final String KEY_A = "rgw_test_usage_a_" + UUID.randomUUID().toString().replace("-", "");
    static final String KEY_B = "rgw_test_usage_b_" + UUID.randomUUID().toString().replace("-", "");

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
        TestData.insertTenant(jdbc, "tenant-a", 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "tenant-a", KEY_A, Set.of(), false);
        TestData.insertTenant(jdbc, "tenant-b", 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "tenant-b", KEY_B, Set.of(), false);
    }

    // ---- helpers ----

    void logRow(String tenant, int in, int out, String cost, String status, OffsetDateTime createdAt) {
        jdbc.sql("""
                        INSERT INTO request_log (request_id, tenant_id, requested_model, input_tokens, output_tokens,
                                                 cost_usd, latency_ms, stream, cache_status, status, http_status, created_at)
                        VALUES (:id, :tenant, 'claude-haiku-5-5', :in, :out, :cost, 5, false, 'miss', :status, 200, :at)
                        """)
                .param("id", UUID.randomUUID()).param("tenant", tenant).param("in", in).param("out", out)
                .param("cost", new BigDecimal(cost)).param("status", status).param("at", createdAt)
                .update();
    }

    static OffsetDateTime at(String isoUtc) {
        return OffsetDateTime.parse(isoUtc);
    }

    HttpResponse<String> usage(String key, String query) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/usage" + query)).timeout(Duration.ofSeconds(10)).GET();
        if (key != null) builder.header("Authorization", "Bearer " + key);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode usageJson(String key, String query) throws Exception {
        HttpResponse<String> response = usage(key, query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    Map<String, Object> sqlSums(String tenant, String from, String to) {
        return jdbc.sql("""
                        SELECT count(*) AS requests, coalesce(sum(input_tokens), 0) AS input_tokens,
                               coalesce(sum(output_tokens), 0) AS output_tokens, coalesce(sum(cost_usd), 0) AS cost_usd
                        FROM request_log WHERE tenant_id = :t AND created_at >= :from AND created_at < :to
                        """)
                .param("t", tenant)
                .param("from", LocalDate.parse(from).atStartOfDay().atOffset(ZoneOffset.UTC))
                .param("to", LocalDate.parse(to).atStartOfDay().atOffset(ZoneOffset.UTC))
                .query().singleRow();
    }

    // ---- AC-t02-9 ----

    @Test
    void ac_t02_9_usageEqualsSqlSumOverTheCallersRowsAndNothingFromAnotherTenant() throws Exception {
        logRow("tenant-a", 100, 20, "0.000020", "ok", at("2026-10-01T00:00:00Z"));       // first instant: in
        logRow("tenant-a", 1_234, 567, "0.000407", "ok", at("2026-10-15T12:00:00Z"));
        logRow("tenant-a", 0, 0, "0", "error", at("2026-10-20T08:00:00Z"));              // a 429: counted
        logRow("tenant-a", 7, 3, "0.000003", "client_cancelled", at("2026-10-31T23:59:59.999Z"));
        logRow("tenant-a", 999, 999, "9.999999", "ok", at("2026-11-01T00:00:00Z"));       // 'to' is exclusive
        logRow("tenant-a", 999, 999, "9.999999", "ok", at("2026-09-30T23:59:59Z"));       // before 'from'
        logRow("tenant-b", 50_000, 50_000, "5.000000", "ok", at("2026-10-10T00:00:00Z")); // other tenant

        JsonNode body = usageJson(KEY_A, "?from=2026-10-01&to=2026-11-01");

        Map<String, Object> sql = sqlSums("tenant-a", "2026-10-01", "2026-11-01");
        assertThat(body.get("tenant_id").asText()).isEqualTo("tenant-a");
        assertThat(body.get("from").asText()).isEqualTo("2026-10-01");
        assertThat(body.get("to").asText()).isEqualTo("2026-11-01");
        assertThat(body.get("requests").asLong()).isEqualTo(((Number) sql.get("requests")).longValue()).isEqualTo(4);
        assertThat(body.get("input_tokens").asLong()).isEqualTo(((Number) sql.get("input_tokens")).longValue())
                .isEqualTo(1_341);
        assertThat(body.get("output_tokens").asLong()).isEqualTo(((Number) sql.get("output_tokens")).longValue())
                .isEqualTo(590);
        assertThat(body.get("cost_usd").isTextual()).isTrue();
        assertThat(body.get("cost_usd").asText()).isEqualTo("0.000430");
        assertThat(new BigDecimal(body.get("cost_usd").asText())).isEqualByComparingTo((BigDecimal) sql.get("cost_usd"));

        JsonNode other = usageJson(KEY_B, "?from=2026-10-01&to=2026-11-01");
        assertThat(other.get("tenant_id").asText()).isEqualTo("tenant-b");
        assertThat(other.get("requests").asLong()).isEqualTo(1);
        assertThat(other.get("cost_usd").asText()).isEqualTo("5.000000");
    }

    @Test
    void defaultRangeIsTheCurrentUtcMonth() throws Exception {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        logRow("tenant-a", 10, 20, "0.000100", "ok", OffsetDateTime.now(ZoneOffset.UTC));
        logRow("tenant-a", 99, 99, "1.000000", "ok", month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).minusSeconds(1));

        JsonNode body = usageJson(KEY_A, "");

        assertThat(body.get("from").asText()).isEqualTo(month.atDay(1).toString());
        assertThat(body.get("to").asText()).isEqualTo(month.plusMonths(1).atDay(1).toString());
        assertThat(body.get("requests").asLong()).isEqualTo(1);
        assertThat(body.get("input_tokens").asLong()).isEqualTo(10);
        assertThat(body.get("output_tokens").asLong()).isEqualTo(20);
        assertThat(body.get("cost_usd").asText()).isEqualTo("0.000100");
    }

    @Test
    void emptyRangeReturnsZerosWithSixDecimalCost() throws Exception {
        JsonNode body = usageJson(KEY_A, "?from=2025-01-01&to=2025-02-01");

        assertThat(body.get("requests").asLong()).isZero();
        assertThat(body.get("input_tokens").asLong()).isZero();
        assertThat(body.get("cost_usd").asText()).isEqualTo("0.000000");
    }

    @Test
    void invalidRangesAre400InvalidRequest() throws Exception {
        for (String query : new String[]{"?from=2026-10-01&to=2026-10-01", "?from=2026-10-02&to=2026-10-01",
                "?from=2025-01-01&to=2026-01-03", "?from=2026-13-01&to=2026-12-01", "?from=yesterday",
                "?from=2026-10-01T00:00:00Z&to=2026-11-01"}) {
            HttpResponse<String> response = usage(KEY_A, query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).at("/error/code").asText()).as(query).isEqualTo("invalid_request");
        }
        // 366 days is the longest range allowed; 367 is not
        assertThat(usage(KEY_A, "?from=2027-01-01&to=2028-01-02").statusCode()).isEqualTo(200);
        assertThat(usage(KEY_A, "?from=2027-01-01&to=2028-01-03").statusCode()).isEqualTo(400);
    }

    @Test
    void withoutAValidKeyIs401() throws Exception {
        assertThat(usage(null, "").statusCode()).isEqualTo(401);
        assertThat(usage("rgw_not_a_real_key", "").statusCode()).isEqualTo(401);
    }

    // ---- rejected requests and cost, through the gateway ----

    HttpResponse<String> chat(String key, int maxTokens) throws Exception {
        String body = """
                {"model":"claude-haiku-5-5","max_tokens":%d,"messages":[{"role":"user","content":"hello there"}]}"""
                .formatted(maxTokens);
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    long logRows(String tenant) {
        return jdbc.sql("SELECT count(*) FROM request_log WHERE tenant_id = :t").param("t", tenant).query(Long.class).single();
    }

    @Test
    void rejectedRequestIsLoggedWithZeroTokensAndCountedAsARequest() throws Exception {
        TestData.insertTenant(jdbc, "tiny", 100, BigDecimal.TEN, Set.of());
        String key = "rgw_test_usage_tiny_" + UUID.randomUUID().toString().replace("-", "");
        TestData.insertKey(jdbc, "tiny", key, Set.of(), false);

        HttpResponse<String> response = chat(key, 200); // reservation > 100 TPM: 400 too large

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).at("/error/code").asText()).isEqualTo("invalid_request");
        await().atMost(Duration.ofSeconds(5)).until(() -> logRows("tiny") == 1);
        Map<String, Object> row = jdbc.sql("SELECT * FROM request_log WHERE tenant_id = 'tiny'").query().singleRow();
        assertThat(row).containsEntry("status", "error").containsEntry("error_code", "invalid_request")
                .containsEntry("input_tokens", 0).containsEntry("output_tokens", 0);
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(CLAUDE.getAllServeEvents()).isEmpty();
        assertThat(usageJson(key, "").get("requests").asLong()).isEqualTo(1);
    }

    @Test
    void costInTheLogRowEqualsTheBudgetIncrementInRedis() throws Exception {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply("claude-haiku-5-5", 1_234, 567, "Hel", "lo"))));

        HttpResponse<String> response = chat(KEY_A, 600);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).until(() -> logRows("tenant-a") == 1);
        BigDecimal logged = jdbc.sql("SELECT cost_usd FROM request_log WHERE tenant_id = 'tenant-a'")
                .query(BigDecimal.class).single();
        // haiku: 1,234 x $0.10 + 567 x $0.50 per Mtok = 406.9 micro-USD -> 407
        assertThat(logged).isEqualByComparingTo("0.000407");
        String budget = redis.opsForValue().get(RedisBudgetLedger.key("tenant-a", YearMonth.now(ZoneOffset.UTC)));
        assertThat(budget).isEqualTo("407");
        assertThat(usageJson(KEY_A, "").get("cost_usd").asText()).isEqualTo("0.000407");
    }
}
