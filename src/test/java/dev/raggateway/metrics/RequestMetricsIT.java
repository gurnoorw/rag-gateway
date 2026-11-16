package dev.raggateway.metrics;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.OllamaNdjson;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/**
 * t04 request metrics end to end: real HTTP through the whole application, WireMock as Claude and Ollama, values
 * read back from {@code /actuator/prometheus} with {@link PrometheusScrape} (AC-t04-1, -2, -7).
 *
 * <p>The meter registry lives as long as the application context, which other tests of this class share, so each
 * test uses its own tenant id and compares with log rows selected by its own request ids (TEAM_RULES). Metrics are
 * recorded after the response is closed (C7), so reads wait with Awaitility.
 */
class RequestMetricsIT extends AbstractIntegrationTest {

    static final String HAIKU = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final WireMockServer CLAUDE = startWireMock();
    static final WireMockServer OLLAMA = startWireMock();

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<UUID> sentRequestIds = new ArrayList<>();
    String tenant;
    String key;

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
        // A tenant id no other test uses: the registry outlives the per-test table truncation.
        tenant = "metrics-" + UUID.randomUUID().toString().substring(0, 8);
        // Obviously fake gateway key, created for this test only.
        key = "rgw_test_metrics_key_" + UUID.randomUUID().toString().replace("-", "");
        TestData.insertTenant(jdbc, tenant, 1_000_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, tenant, key, Set.of(), false);
    }

    // ---- helpers ----

    HttpResponse<String> chat(String model, boolean stream) throws Exception {
        String body = """
                {"model":"%s","stream":%s,"max_tokens":64,"messages":[{"role":"user","content":"hi"}]}"""
                .formatted(model, stream);
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        sentRequestIds.add(UUID.fromString(response.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow()));
        return response;
    }

    static void claudeReplies(int in, int out) {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream").withBody(ClaudeSse.reply(HAIKU, in, out, "a", "b"))));
    }

    PrometheusScrape scrape() {
        return PrometheusScrape.fetch(baseUrl());
    }

    double requestsOfTenant() {
        return scrape().sum("gateway_requests_total", Map.of("tenant", tenant));
    }

    /** Log rows of this test's own requests, once every one of them is written. */
    List<Map<String, Object>> ownLogRows() {
        await().atMost(Duration.ofSeconds(5)).until(() -> logRows().size() == sentRequestIds.size());
        return logRows();
    }

    private List<Map<String, Object>> logRows() {
        return jdbc.sql("SELECT * FROM request_log WHERE request_id = ANY(?)")
                .param(sentRequestIds.toArray(UUID[]::new)).query().listOfRows();
    }

    static long sum(List<Map<String, Object>> rows, String column) {
        return rows.stream().mapToLong(r -> ((Number) r.get(column)).longValue()).sum();
    }

    // ---- AC-t04-1 ----

    @Test
    void ac_t04_1_fiveRequestsForATenantShowAsFiveOnThePrometheusEndpoint() throws Exception {
        claudeReplies(11, 3);

        for (int i = 0; i < 5; i++) assertThat(chat(HAIKU, i % 2 == 0).statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(5)).until(() -> requestsOfTenant() == 5.0);
        PrometheusScrape scrape = scrape();
        assertThat(scrape.sum("gateway_requests_total", Map.of("tenant", tenant, "provider", "claude",
                "model", HAIKU, "status_class", "2xx", "code", "none"))).isEqualTo(5.0);
        assertThat(ownLogRows()).hasSize(5);
    }

    // ---- AC-t04-2 ----

    @Test
    void ac_t04_2_tokenCountersEqualTheUsageOfThoseFiveRequests() throws Exception {
        claudeReplies(17, 6);

        for (int i = 0; i < 5; i++) assertThat(chat(HAIKU, i % 2 == 1).statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(5)).until(() -> requestsOfTenant() == 5.0);
        List<Map<String, Object>> rows = ownLogRows();
        PrometheusScrape scrape = scrape();
        double in = scrape.sum("gateway_tokens_total", Map.of("tenant", tenant, "direction", "in"));
        double out = scrape.sum("gateway_tokens_total", Map.of("tenant", tenant, "direction", "out"));
        assertThat(in).isEqualTo(sum(rows, "input_tokens")).isEqualTo(5 * 17);
        assertThat(out).isEqualTo(sum(rows, "output_tokens")).isEqualTo(5 * 6);
        assertThat(scrape.sum("gateway_tokens_total", Map.of("tenant", tenant, "model", HAIKU))).isEqualTo(5 * 23);
        BigDecimal loggedCost = rows.stream().map(r -> (BigDecimal) r.get("cost_usd")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(scrape.sum("gateway_cost_usd_total", Map.of("tenant", tenant)))
                .isCloseTo(loggedCost.doubleValue(), within(1e-9));
    }

    @Test
    void ac_t04_2_withFallbackTokensAreCountedUnderTheModelThatProducedThem() throws Exception {
        CLAUDE.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"fake 500\"}}")));
        OLLAMA.stubFor(post("/api/chat").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", OllamaNdjson.CONTENT_TYPE).withBody(OllamaNdjson.reply(LLAMA, 9, 2, "x", "y"))));

        HttpResponse<String> response = chat(HAIKU, true);

        assertThat(response.statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).until(() -> requestsOfTenant() == 1.0);
        Map<String, Object> row = ownLogRows().get(0);
        PrometheusScrape scrape = scrape();
        assertThat(scrape.sum("gateway_requests_total", Map.of("tenant", tenant, "provider", "ollama", "model", LLAMA)))
                .isEqualTo(1.0);
        assertThat(scrape.sum("gateway_tokens_total", Map.of("tenant", tenant, "model", LLAMA, "direction", "in")))
                .isEqualTo(((Number) row.get("input_tokens")).doubleValue()).isEqualTo(9);
        assertThat(scrape.sum("gateway_tokens_total", Map.of("tenant", tenant, "model", LLAMA, "direction", "out")))
                .isEqualTo(((Number) row.get("output_tokens")).doubleValue()).isEqualTo(2);
        assertThat(scrape.sum("gateway_tokens_total", Map.of("tenant", tenant, "model", HAIKU)))
                .as("Claude's HTTP 500s are billed zero (D-34)").isZero();
    }

    // ---- AC-t04-7 ----

    @Test
    void ac_t04_7_histogramsExposeTheConfiguredBucketsSoP50P95P99CanBeComputed() throws Exception {
        claudeReplies(5, 2);
        for (int i = 0; i < 5; i++) assertThat(chat(HAIKU, true).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).until(() -> requestsOfTenant() == 5.0);
        PrometheusScrape scrape = scrape();

        Map<String, String> claudeHaiku = Map.of("provider", "claude", "model", HAIKU);
        for (String family : List.of("gateway_request_duration_seconds", "gateway_request_ttft_seconds")) {
            var buckets = buckets(scrape, family, claudeHaiku);
            assertThat(buckets.keySet()).containsExactlyElementsOf(edgesInSeconds(MetricsConfig.LATENCY_BUCKETS));
            assertQuantilesComputable(scrape, family, claudeHaiku, buckets);
        }
        var overhead = buckets(scrape, "gateway_overhead_seconds", Map.of("provider", "claude"));
        assertThat(overhead.keySet()).containsExactlyElementsOf(edgesInSeconds(MetricsConfig.OVERHEAD_BUCKETS));
        assertQuantilesComputable(scrape, "gateway_overhead_seconds", Map.of("provider", "claude"), overhead);
        // No client-side percentiles (summary quantiles) anywhere in the gateway's own metrics.
        assertThat(scrape.samples()).filteredOn(s -> s.name().startsWith("gateway_"))
                .noneMatch(s -> s.labels().containsKey("quantile"));
    }

    @Test
    void fixedLabelCountersAreExposedBeforeAnyFailure() {
        PrometheusScrape scrape = scrape();

        assertThat(scrape.series("gateway_ratelimit_failopen_total")).extracting(s -> s.label("component"))
                .contains("limiter", "budget");
        assertThat(scrape.series("gateway_requestlog_write_failures_total")).hasSize(1);
    }

    /** {@code le} edge in seconds to cumulative count, sorted, for one series; +Inf included. */
    static Map<Double, Double> buckets(PrometheusScrape scrape, String family, Map<String, String> labels) {
        return scrape.series(family + "_bucket").stream()
                .filter(s -> labels.entrySet().stream().allMatch(e -> e.getValue().equals(s.label(e.getKey()))))
                .collect(Collectors.toMap(s -> parseLe(s.label("le")), PrometheusScrape.Sample::value, (a, b) -> a + b,
                        java.util.TreeMap::new));
    }

    static double parseLe(String le) {
        return "+Inf".equals(le) ? Double.POSITIVE_INFINITY : Double.parseDouble(le);
    }

    static List<Double> edgesInSeconds(List<Duration> edges) {
        List<Double> seconds = new ArrayList<>(edges.stream().map(d -> d.toNanos() / 1e9).toList());
        seconds.add(Double.POSITIVE_INFINITY);
        return seconds;
    }

    /**
     * What Prometheus' {@code histogram_quantile} needs: cumulative, non-decreasing counts whose +Inf bucket equals
     * {@code _count}. The quantile is then interpolated inside a finite bucket; check it lands in one.
     */
    static void assertQuantilesComputable(PrometheusScrape scrape, String family, Map<String, String> labels,
                                          Map<Double, Double> buckets) {
        List<Double> counts = new ArrayList<>(buckets.values());
        assertThat(counts).isSortedAccordingTo(Comparator.naturalOrder());
        double total = scrape.sum(family + "_count", labels);
        assertThat(total).isGreaterThanOrEqualTo(5);
        assertThat(buckets.get(Double.POSITIVE_INFINITY)).isEqualTo(total);
        for (double q : new double[] {0.5, 0.95, 0.99}) {
            double rank = q * total;
            Function<Map.Entry<Double, Double>, Boolean> reaches = e -> e.getValue() >= rank;
            Double edge = buckets.entrySet().stream().filter(reaches::apply).findFirst().orElseThrow().getKey();
            assertThat(edge).as("p%s of %s falls in a finite bucket", (int) (q * 100), family).isFinite();
        }
    }
}
