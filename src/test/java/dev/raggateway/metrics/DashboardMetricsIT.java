package dev.raggateway.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.raggateway.support.PrometheusScrape.prometheusBase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * t04 Dev B: the Grafana dashboard ({@code ops/grafana/dashboards/rag-gateway.json}) against the running
 * application. AC-t04-6 inside {@code ./gradlew test}: the JSON parses, every panel query follows the PromQL
 * rules (counters only through {@code rate()}, percentiles only from {@code histogram_quantile} over
 * {@code sum by (le, ...)} of bucket rates), and after a mix of traffic through WireMock (success, a 429, a
 * provider 500 answered by the fallback) every metric a query names exists on {@code /actuator/prometheus}.
 * {@code scripts/check_dashboard.py} applies the same rules to a running app. AC-t04-4 is checked on
 * {@code docker-compose.yml} itself; AC-t04-5 needs the compose stack and is {@code scripts/check_obs.sh}.
 */
// Its own context (WireMock URLs). Every cached context holds a Hikari pool on the shared Postgres, whose default
// max_connections (100) the suite already nearly fills; a small pool and closing the context after the class keep
// this test from starving later ones ("too many clients already").
@DirtiesContext
class DashboardMetricsIT extends AbstractIntegrationTest {

    static final Path DASHBOARD = Path.of("ops/grafana/dashboards/rag-gateway.json");
    static final Path DATASOURCES = Path.of("ops/grafana/provisioning/datasources/prometheus.yml");
    static final Path COMPOSE = Path.of("docker-compose.yml");

    /**
     * The request-level metrics recorded by {@code RequestMetricsListener} (t04-a, D-38). The dashboard must chart
     * each of them; like every other metric a panel names, each must be on the scrape page.
     */
    static final Set<String> REQUEST_METRICS = Set.of(
            prometheusBase(MetricNames.REQUESTS) + "_total",
            prometheusBase(MetricNames.REQUEST_DURATION) + "_seconds_bucket",
            prometheusBase(MetricNames.TIME_TO_FIRST_TOKEN) + "_seconds_bucket",
            prometheusBase(MetricNames.GATEWAY_OVERHEAD) + "_seconds_bucket",
            prometheusBase(MetricNames.TOKENS) + "_total",
            prometheusBase(MetricNames.COST_USD) + "_total");

    /** Labels of Resilience4j's own breaker series; outside the {@code gateway_*} allow-list (D-38). */
    static final Set<String> RESILIENCE4J_LABELS = Set.of("name", "state", "kind");

    static final String MODEL = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final String FAIL_PRIMARY = "primary-fails";
    static final ObjectMapper JSON = new ObjectMapper();
    static final WireMockServer CLAUDE = startWireMock();
    static final WireMockServer OLLAMA = startWireMock();

    // Obviously fake gateway keys, created for this test only.
    static final String KEY = "rgw_test_dashboard_" + UUID.randomUUID().toString().replace("-", "");
    static final String SUSPENDED_KEY = "rgw_test_dashboard_suspended_" + UUID.randomUUID().toString().replace("-", "");

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static WireMockServer startWireMock() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void providersAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
        registry.add("gateway.providers.ollama.base-url", OLLAMA::baseUrl);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
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
        // A budget of 0 means a suspended tenant: every request is a 429 budget_exceeded (D-36).
        TestData.insertTenant(jdbc, "suspended", 1_000_000, BigDecimal.ZERO, Set.of());
        TestData.insertKey(jdbc, "suspended", SUSPENDED_KEY, Set.of(), false);
    }

    // ---- AC-t04-6, static: the dashboard JSON and its queries ----

    @Test
    void ac_t04_6_dashboardParsesAndEveryQueryFollowsThePromQlRules() throws IOException {
        JsonNode dashboard = JSON.readTree(Files.readString(DASHBOARD));
        String uid = provisionedDatasourceUid();
        List<JsonNode> panels = panels(dashboard);

        assertThat(dashboard.path("uid").asText()).isEqualTo("rag-gateway");
        assertThat(panels).as("panels").isNotEmpty();
        assertThat(panels.stream().map(p -> p.path("id").asInt()).distinct().count()).as("unique panel ids")
                .isEqualTo(panels.size());
        List<String> problems = new ArrayList<>();
        for (JsonNode panel : panels) {
            String title = panel.path("title").asText();
            if (panel.path("targets").isEmpty()) problems.add(title + ": no query");
            for (JsonNode target : panel.path("targets")) {
                String expr = target.path("expr").asText();
                String where = title + " / " + target.path("refId").asText() + ": " + expr;
                JsonNode ds = target.has("datasource") ? target.path("datasource") : panel.path("datasource");
                if (!uid.equals(ds.path("uid").asText())) problems.add(where + ": datasource is not '" + uid + "'");
                problems.addAll(PromQl.ruleViolations(expr).stream().map(v -> where + ": " + v).toList());
                for (String label : PromQl.groupingLabels(expr)) {
                    boolean breakerOnly = PromQl.metricNames(expr).stream().allMatch(n -> n.startsWith("resilience4j_"));
                    Set<String> allowed = breakerOnly ? RESILIENCE4J_LABELS : MetricNames.ALLOWED_LABELS;
                    if (!label.equals("le") && !allowed.contains(label)) {
                        problems.add(where + ": groups by '" + label + "', not an allowed label");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void thePromQlRulesRejectCountersWithoutRateAndPercentilesNotFromBuckets() {
        assertThat(PromQl.ruleViolations("gateway_requests_total")).isNotEmpty();
        assertThat(PromQl.ruleViolations("histogram_quantile(0.99, rate(gateway_overhead_seconds_bucket[5m]))"))
                .as("no sum by (le, ...)").isNotEmpty();
        assertThat(PromQl.ruleViolations("sum(gateway_overhead_seconds_bucket)")).isNotEmpty();
        assertThat(PromQl.ruleViolations("gateway_request_duration_seconds{quantile=\"0.99\"}")).isNotEmpty();
        assertThat(PromQl.ruleViolations("1e3")).as("names no metric").isNotEmpty();
        assertThat(PromQl.ruleViolations(
                "histogram_quantile(0.95, sum by (le, provider) (rate(gateway_overhead_seconds_bucket[5m])))")).isEmpty();
        assertThat(PromQl.metricNames("sum by (tenant) (rate(gateway_cost_usd_total{tenant=\"a\"}[5m])) * 3600"))
                .containsExactly("gateway_cost_usd_total");
    }

    // ---- AC-t04-6, dynamic: every metric a panel names is on the scrape page after mixed traffic ----

    @Test
    void ac_t04_6_everyMetricAPanelNamesExistsOnTheScrapePageAfterMixedTraffic() throws Exception {
        List<String> exprs = panels(JSON.readTree(Files.readString(DASHBOARD))).stream()
                .flatMap(p -> p.path("targets").valueStream()).map(t -> t.path("expr").asText()).toList();
        Set<String> named = new TreeSet<>();
        exprs.forEach(e -> named.addAll(PromQl.metricNames(e)));
        assertThat(named).as("the dashboard charts every request-level metric").containsAll(REQUEST_METRICS);
        CLAUDE.stubFor(post("/v1/messages").atPriority(5).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream").withBody(ClaudeSse.reply(MODEL, 12, 3, "Hel", "lo"))));
        CLAUDE.stubFor(post("/v1/messages").atPriority(1).withRequestBody(containing(FAIL_PRIMARY))
                .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"fake 500\"}}")));
        OLLAMA.stubFor(post("/api/chat").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", OllamaNdjson.CONTENT_TYPE)
                .withBody(OllamaNdjson.reply(LLAMA, 12, 2, "from ", "llama"))));

        Map<String, HttpResponse<String>> responses = new LinkedHashMap<>();
        responses.put("stream success", chat(KEY, true, "hello"));
        responses.put("json success", chat(KEY, false, "hello"));
        responses.put("primary 500, fallback answers", chat(KEY, true, FAIL_PRIMARY));
        responses.put("budget 429", chat(SUSPENDED_KEY, false, "hello"));

        assertThat(responses.get("stream success").statusCode()).isEqualTo(200);
        assertThat(responses.get("json success").statusCode()).isEqualTo(200);
        HttpResponse<String> fallback = responses.get("primary 500, fallback answers");
        assertThat(fallback.statusCode()).isEqualTo(200);
        assertThat(fallback.headers().firstValue(GatewayHeaders.PROVIDER)).hasValue("ollama");
        assertThat(responses.get("budget 429").statusCode()).isEqualTo(429);
        // Every request has finished its completion listeners: its log row exists (selected by this test's own ids).
        List<UUID> ids = responses.values().stream()
                .map(r -> UUID.fromString(r.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow())).toList();
        await().atMost(Duration.ofSeconds(10)).until(() -> ids.stream().allMatch(id -> jdbc.sql(
                "SELECT count(*) FROM request_log WHERE request_id = ?").param(id).query(Long.class).single() == 1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(missing(PrometheusScrape.fetch(baseUrl()), named)).as("metrics missing from the scrape")
                        .isEmpty());
        PrometheusScrape scrape = PrometheusScrape.fetch(baseUrl());
        List<String> labelProblems = new ArrayList<>();
        for (String expr : exprs) {
            List<String> present = PromQl.metricNames(expr).stream().filter(n -> !scrape.series(n).isEmpty()).toList();
            if (present.isEmpty()) continue;
            Set<String> labels = new LinkedHashSet<>();
            present.forEach(n -> labels.addAll(scrape.labelNames(n::equals)));
            for (String label : PromQl.groupingLabels(expr)) {
                if (!label.equals("le") && !labels.contains(label)) labelProblems.add(expr + ": no series has '" + label + "'");
            }
        }
        assertThat(labelProblems).as("grouping labels missing from the scrape").isEmpty();
    }

    // ---- AC-t04-4: the obs profile adds exactly Prometheus and Grafana ----

    @Test
    @SuppressWarnings("unchecked")
    void ac_t04_4_composeHasTwoServicesByDefaultAndFourWithTheObsProfile() throws IOException {
        Map<String, Object> compose = new Yaml().load(Files.readString(COMPOSE));
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) compose.get("services");
        Set<String> always = new TreeSet<>();
        Set<String> obs = new TreeSet<>();
        services.forEach((name, service) -> {
            List<String> profiles = (List<String>) service.getOrDefault("profiles", List.of());
            if (profiles.isEmpty()) always.add(name);
            else if (profiles.equals(List.of("obs"))) obs.add(name);
        });

        assertThat(always).containsExactly("postgres", "redis");
        assertThat(obs).containsExactly("grafana", "prometheus");
        assertThat((List<String>) services.get("prometheus").get("volumes"))
                .contains("./ops/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro");
        assertThat((List<String>) services.get("grafana").get("volumes"))
                .contains("./ops/grafana/provisioning:/etc/grafana/provisioning:ro",
                        "./ops/grafana/dashboards:/var/lib/grafana/dashboards:ro");
    }

    // ---- helpers ----

    HttpResponse<String> chat(String key, boolean stream, String prompt) throws Exception {
        String body = """
                {"model":"%s","stream":%s,"max_tokens":64,"messages":[{"role":"user","content":"%s"}]}"""
                .formatted(MODEL, stream, prompt);
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static Set<String> missing(PrometheusScrape scrape, Set<String> names) {
        Set<String> missing = new TreeSet<>();
        for (String n : names) if (scrape.series(n).isEmpty()) missing.add(n);
        return missing;
    }

    static List<JsonNode> panels(JsonNode dashboard) {
        List<JsonNode> panels = new ArrayList<>();
        for (JsonNode p : dashboard.path("panels")) {
            if ("row".equals(p.path("type").asText())) p.path("panels").forEach(panels::add);
            else panels.add(p);
        }
        return panels;
    }

    static String provisionedDatasourceUid() throws IOException {
        Matcher m = Pattern.compile("(?m)^\\s*uid:\\s*(\\S+)").matcher(Files.readString(DATASOURCES));
        assertThat(m.find()).as("datasource uid in " + DATASOURCES).isTrue();
        return m.group(1);
    }

    /** The PromQL subset the dashboard uses; the same rules as {@code scripts/check_dashboard.py}. */
    static final class PromQl {
        private static final Set<String> KEYWORDS = Set.of("by", "without", "on", "ignoring", "group_left",
                "group_right", "bool", "and", "or", "unless", "offset", "inf", "nan");
        private static final Pattern IDENT = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
        private static final Pattern GROUPING = Pattern.compile("\\b(?:by|without)\\s*\\(([^)]*)\\)");
        private static final Pattern COUNTER_IN_RATE =
                Pattern.compile("\\b(?:rate|irate|increase)\\(\\s*([a-zA-Z_:][a-zA-Z0-9_:]*_total)\\b");
        private static final Pattern BUCKET_QUANTILE = Pattern.compile(
                "histogram_quantile\\(\\s*0?\\.\\d+\\s*,\\s*sum\\s+by\\s*\\(\\s*le\\s*(?:,[^)]*)?\\)\\s*"
                        + "\\(\\s*rate\\(\\s*([a-zA-Z_:][a-zA-Z0-9_:]*_bucket)\\s*\\[");

        /** Identifiers that are not functions, keywords, label names, label values or durations. */
        static List<String> metricNames(String expr) {
            String s = expr.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"")
                    .replaceAll("\\{[^}]*}", "")
                    .replaceAll("\\[[^]]*]", "")
                    .replaceAll("\\b(?:by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)", "");
            List<String> names = new ArrayList<>();
            Matcher m = IDENT.matcher(s);
            while (m.find()) {
                String word = m.group();
                if (KEYWORDS.contains(word.toLowerCase())) continue;
                if (s.substring(m.end()).matches("(?s)\\s*\\(.*")) continue; // a function call
                char before = m.start() > 0 ? s.charAt(m.start() - 1) : ' ';
                if (Character.isDigit(before) || before == '.') continue;    // the "e" in 1e3
                if (!names.contains(word)) names.add(word);
            }
            return names;
        }

        static List<String> groupingLabels(String expr) {
            List<String> labels = new ArrayList<>();
            Matcher m = GROUPING.matcher(expr);
            while (m.find()) {
                for (String l : m.group(1).split(",")) if (!l.isBlank()) labels.add(l.strip());
            }
            return labels;
        }

        static List<String> ruleViolations(String expr) {
            List<String> problems = new ArrayList<>();
            List<String> names = metricNames(expr);
            if (names.isEmpty()) problems.add("names no metric");
            if (!all(COUNTER_IN_RATE, expr).equals(occurrences(expr, "_total", names))) {
                problems.add("a counter outside rate()/increase()");
            }
            if (!all(BUCKET_QUANTILE, expr).equals(occurrences(expr, "_bucket", names))) {
                problems.add("a bucket series outside histogram_quantile(q, sum by (le, ...) (rate(..._bucket[w])))");
            }
            if (expr.matches("(?s).*\\bquantile\\s*=.*")) problems.add("client-side quantile label");
            return problems;
        }

        /** Every capture of {@code p} in order, so a counter used twice must be inside rate() twice. */
        private static List<String> all(Pattern p, String expr) {
            List<String> found = new ArrayList<>();
            Matcher m = p.matcher(expr);
            while (m.find()) found.add(m.group(1));
            return found;
        }

        /** Each occurrence of a metric with this suffix, in order of appearance in the expression. */
        private static List<String> occurrences(String expr, String suffix, List<String> names) {
            List<String> found = new ArrayList<>();
            Matcher m = IDENT.matcher(expr);
            while (m.find()) {
                if (m.group().endsWith(suffix) && names.contains(m.group())) found.add(m.group());
            }
            return found;
        }
    }
}
