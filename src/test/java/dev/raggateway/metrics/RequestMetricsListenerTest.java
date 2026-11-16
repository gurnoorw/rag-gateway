package dev.raggateway.metrics;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.cost.PriceTable;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.ratelimit.FailOpenCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Label values, durations, tokens and cost of {@link RequestMetricsListener} on a plain registry (D-38). */
class RequestMetricsListenerTest {

    static final String OPUS = "claude-opus-5-5";
    static final String HAIKU = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final long MS = 1_000_000L;
    static final long START = 1_000 * MS;

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CostCalculator costs = new CostCalculator(new PriceTable(Map.of(
            OPUS, new PriceTable.Price(new BigDecimal("4.00"), new BigDecimal("20.00")),
            HAIKU, new PriceTable.Price(new BigDecimal("0.10"), new BigDecimal("0.50")),
            LLAMA, new PriceTable.Price(BigDecimal.ZERO, BigDecimal.ZERO))));
    /** Far later than any finish time: a duration that used it instead of finishNanos would be obvious. */
    final long listenerRunsAt = START + 3_600_000 * MS;
    RequestMetricsListener listener;

    @BeforeEach
    void setUp() {
        registry.config().meterFilter(MetricsConfig.histogramBuckets());
        listener = new RequestMetricsListener(registry, costs, Set.of(OPUS, HAIKU, LLAMA), () -> listenerRunsAt);
    }

    static RequestContext ctx(String tenant, String model) {
        var caller = new ApiKeyPrincipal(UUID.randomUUID().toString(),
                new Tenant(tenant, "T", 100_000, BigDecimal.TEN, Set.of()), Set.of(), false);
        var request = new ChatRequest(model, List.of(new Message(Role.USER, "hello")), 64, null, null, List.of(),
                true, false, null);
        return new RequestContext(UUID.randomUUID().toString(), START, caller, request);
    }

    static Attempt attempt(String provider, String model, boolean fallback, AttemptOutcome outcome, int in, int out,
                           long durationMs) {
        return new Attempt(provider, model, fallback, outcome, new Usage(in, out), false, durationMs * MS, -1, null);
    }

    double requests(String... tags) {
        Counter c = registry.find(MetricNames.REQUESTS).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    Timer timer(String name, String... tags) {
        return registry.find(name).tags(tags).timer();
    }

    double tokens(String tenant, String model, String direction) {
        Counter c = registry.find(MetricNames.TOKENS).tags("tenant", tenant, "model", model, "direction", direction).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void successCountsOneRequestWithServingProviderAndCatalogModel() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("claude", HAIKU, false, AttemptOutcome.SUCCEEDED, 10, 4, 300));
        ctx.markFirstTokenSent(START + 120 * MS);
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 400 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("tenant", "acme", "provider", "claude", "model", HAIKU, "status_class", "2xx",
                "code", "none")).isEqualTo(1.0);
        assertThat(timer(MetricNames.REQUEST_DURATION, "provider", "claude", "model", HAIKU)
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(400.0);
        assertThat(timer(MetricNames.TIME_TO_FIRST_TOKEN, "provider", "claude", "model", HAIKU)
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
        assertThat(timer(MetricNames.GATEWAY_OVERHEAD, "provider", "claude").totalTime(TimeUnit.MILLISECONDS))
                .as("total 400 ms − attempt 300 ms").isEqualTo(100.0);
        assertThat(tokens("acme", HAIKU, "in")).isEqualTo(10.0);
        assertThat(tokens("acme", HAIKU, "out")).isEqualTo(4.0);
    }

    @Test
    void histogramsCarryNoTenantLabel() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("claude", HAIKU, false, AttemptOutcome.SUCCEEDED, 1, 1, 10));
        ctx.markFirstTokenSent(START + MS);
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 20 * MS);

        listener.onRequestFinished(ctx);

        for (String name : List.of(MetricNames.REQUEST_DURATION, MetricNames.TIME_TO_FIRST_TOKEN, MetricNames.GATEWAY_OVERHEAD)) {
            assertThat(registry.find(name).timers()).isNotEmpty().allSatisfy(t ->
                    assertThat(t.getId().getTags()).noneMatch(tag -> tag.getKey().equals("tenant")));
        }
        assertThat(timer(MetricNames.GATEWAY_OVERHEAD).getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactly("provider");
    }

    @Test
    void durationEndsAtFinishNanosNotWhenTheListenerRuns() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("claude", HAIKU, false, AttemptOutcome.SUCCEEDED, 1, 1, 50));
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 75 * MS);

        listener.onRequestFinished(ctx);

        assertThat(timer(MetricNames.REQUEST_DURATION).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(75.0);
        assertThat(timer(MetricNames.GATEWAY_OVERHEAD).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25.0);
    }

    @Test
    void noTokenSentMeansNoTtftSample() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("claude", HAIKU, false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, 0, 0, 30));
        ctx.finish(RequestOutcome.ERROR, ErrorCode.PROVIDER_ERROR, 502, START + 40 * MS);

        listener.onRequestFinished(ctx);

        assertThat(registry.find(MetricNames.TIME_TO_FIRST_TOKEN).timers()).isEmpty();
        assertThat(requests("provider", "claude", "status_class", "5xx", "code", "provider_error")).isEqualTo(1.0);
    }

    @Test
    void fallbackCountsTokensPerAttemptUnderEachAttemptsModelAndOverheadExcludesEveryAttempt() {
        var ctx = ctx("acme", OPUS);
        // A timeout before the first token keeps an estimated input (D-34), so the failed attempt has usage too.
        ctx.recordAttempt(attempt("claude", OPUS, false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, 100, 0, 1_000));
        ctx.recordAttempt(attempt("claude", OPUS, false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, 100, 0, 1_000));
        ctx.recordAttempt(attempt("ollama", LLAMA, true, AttemptOutcome.SUCCEEDED, 12, 7, 500));
        ctx.markFirstTokenSent(START + 2_700 * MS);
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 3_000 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("provider", "ollama", "model", LLAMA)).isEqualTo(1.0);
        assertThat(tokens("acme", OPUS, "in")).isEqualTo(200.0);
        assertThat(tokens("acme", OPUS, "out")).isZero();
        assertThat(tokens("acme", LLAMA, "in")).isEqualTo(12.0);
        assertThat(tokens("acme", LLAMA, "out")).isEqualTo(7.0);
        assertThat(tokens("acme", OPUS, "in") + tokens("acme", LLAMA, "in")).isEqualTo(ctx.billedUsage().inputTokens());
        assertThat(timer(MetricNames.GATEWAY_OVERHEAD, "provider", "ollama").totalTime(TimeUnit.MILLISECONDS))
                .as("3000 − (1000 + 1000 + 500)").isEqualTo(500.0);
        // 200 × $4/Mtok = 800 micro-USD; Ollama is free.
        assertThat(registry.find(MetricNames.COST_USD).tags("tenant", "acme").counter().count())
                .isCloseTo(0.000800, within(1e-12));
    }

    @Test
    void overheadExcludesRetryBackoffAndOtherDeliberateWaits() {
        var ctx = ctx("acme", OPUS);
        ctx.recordAttempt(attempt("claude", OPUS, false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, 0, 0, 1_000));
        ctx.addWaitNanos(250 * MS); // the router's backoff before the second attempt (D-39)
        ctx.recordAttempt(attempt("claude", OPUS, false, AttemptOutcome.SUCCEEDED, 10, 2, 500));
        ctx.markFirstTokenSent(START + 1_700 * MS);
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 1_760 * MS);

        listener.onRequestFinished(ctx);

        assertThat(timer(MetricNames.GATEWAY_OVERHEAD, "provider", "claude").totalTime(TimeUnit.MILLISECONDS))
                .as("1760 − (1000 + 500) − 250 wait").isEqualTo(10.0);
        assertThat(timer(MetricNames.REQUEST_DURATION, "provider", "claude", "model", OPUS).totalTime(TimeUnit.MILLISECONDS))
                .as("the user still waited the whole time").isEqualTo(1_760.0);
    }

    @Test
    void costEqualsTheCostCalculatorsAmount() {
        var ctx = ctx("acme", OPUS);
        ctx.recordAttempt(attempt("claude", OPUS, false, AttemptOutcome.SUCCEEDED, 1_000, 500, 10));
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 20 * MS);

        listener.onRequestFinished(ctx);

        assertThat(registry.find(MetricNames.COST_USD).tags("tenant", "acme").counter().count())
                .isCloseTo(costs.costUsd(ctx.attempts()).doubleValue(), within(1e-12))
                .isCloseTo(0.014, within(1e-12));
    }

    @Test
    void unknownRequestedModelIsLabelledOtherAndNoProviderIsNone() {
        String raw = "gpt-made-up " + UUID.randomUUID();
        var ctx = ctx("acme", raw);
        ctx.finish(RequestOutcome.ERROR, ErrorCode.INVALID_REQUEST, 400, START + 2 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("tenant", "acme", "provider", "none", "model", "other", "status_class", "4xx",
                "code", "invalid_request")).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags())
                .noneMatch(t -> t.getValue().contains(raw) || t.getValue().equals(ctx.requestId())));
        assertThat(timer(MetricNames.GATEWAY_OVERHEAD, "provider", "none").totalTime(TimeUnit.MILLISECONDS))
                .as("no attempt: overhead is the whole request").isEqualTo(2.0);
    }

    @Test
    void attemptModelOutsideTheCatalogIsAlsoOther() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("ollama", "not-in-catalog", true, AttemptOutcome.SUCCEEDED, 3, 2, 5));
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 10 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("provider", "ollama", "model", "other")).isEqualTo(1.0);
        assertThat(tokens("acme", "other", "in")).isEqualTo(3.0);
    }

    @Test
    void rejectedBeforeTheProviderKeepsTheCatalogModelItAskedFor() {
        var ctx = ctx("acme", HAIKU);
        ctx.finish(RequestOutcome.ERROR, ErrorCode.RATE_LIMITED, 429, START + 3 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("provider", "none", "model", HAIKU, "status_class", "4xx", "code", "rate_limited"))
                .isEqualTo(1.0);
        assertThat(registry.find(MetricNames.TOKENS).counters()).isEmpty();
        assertThat(registry.find(MetricNames.COST_USD).counters()).isEmpty();
    }

    @Test
    void cacheHitIsProviderCacheAndItsOverheadIsItsTotal() {
        var ctx = ctx("acme", HAIKU);
        ctx.setCacheStatus(CacheStatus.HIT);
        ctx.markFirstTokenSent(START + 2 * MS);
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 4 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("provider", "cache", "model", HAIKU, "status_class", "2xx")).isEqualTo(1.0);
        assertThat(timer(MetricNames.GATEWAY_OVERHEAD, "provider", "cache").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(4.0);
        assertThat(timer(MetricNames.TIME_TO_FIRST_TOKEN, "provider", "cache").count()).isEqualTo(1);
    }

    @Test
    void clientCancelBeforeAnythingWasSentIs4xxWithCodeNone() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("claude", HAIKU, false, AttemptOutcome.CLIENT_CANCELLED, 10, 0, 5));
        ctx.finish(RequestOutcome.CLIENT_CANCELLED, null, DefaultGatewayPipeline.CLIENT_CLOSED_REQUEST, START + 6 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("provider", "claude", "status_class", "4xx", "code", "none")).isEqualTo(1.0);
    }

    @Test
    void streamFailingAfterTheFirstTokenIs2xxWithItsErrorCode() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("claude", HAIKU, false, AttemptOutcome.FAILED_MID_STREAM, 10, 3, 50));
        ctx.markFirstTokenSent(START + 10 * MS);
        ctx.finish(RequestOutcome.ERROR, ErrorCode.PROVIDER_ERROR, 200, START + 60 * MS);

        listener.onRequestFinished(ctx);

        assertThat(requests("provider", "claude", "status_class", "2xx", "code", "provider_error")).isEqualTo(1.0);
    }

    @Test
    void statusClassBoundaries() {
        assertThat(RequestMetricsListener.statusClass(200)).isEqualTo("2xx");
        assertThat(RequestMetricsListener.statusClass(399)).isEqualTo("2xx");
        assertThat(RequestMetricsListener.statusClass(400)).isEqualTo("4xx");
        assertThat(RequestMetricsListener.statusClass(499)).isEqualTo("4xx");
        assertThat(RequestMetricsListener.statusClass(503)).isEqualTo("5xx");
        assertThat(RequestMetricsListener.statusClass(0)).isEqualTo("5xx");
    }

    @Test
    void ac_t04_7_timersHaveExactlyTheConfiguredBucketsAndNoClientSidePercentiles() {
        var ctx = ctx("acme", HAIKU);
        ctx.recordAttempt(attempt("claude", HAIKU, false, AttemptOutcome.SUCCEEDED, 1, 1, 10));
        ctx.markFirstTokenSent(START + MS);
        ctx.finish(RequestOutcome.SUCCESS, null, 200, START + 20 * MS);

        listener.onRequestFinished(ctx);

        assertBuckets(timer(MetricNames.REQUEST_DURATION), MetricsConfig.LATENCY_BUCKETS);
        assertBuckets(timer(MetricNames.TIME_TO_FIRST_TOKEN), MetricsConfig.LATENCY_BUCKETS);
        assertBuckets(timer(MetricNames.GATEWAY_OVERHEAD), MetricsConfig.OVERHEAD_BUCKETS);
        assertThat(MetricsConfig.LATENCY_BUCKETS).hasSize(12).contains(Duration.ofSeconds(5));
        assertThat(MetricsConfig.OVERHEAD_BUCKETS).hasSize(10).contains(Duration.ofMillis(50));
    }

    static void assertBuckets(Timer timer, List<Duration> expected) {
        var snapshot = timer.takeSnapshot();
        assertThat(snapshot.percentileValues()).isEmpty();
        assertThat(Arrays.stream(snapshot.histogramCounts()).map(CountAtBucket::bucket).toList())
                .containsExactlyElementsOf(expected.stream().map(d -> (double) d.toNanos()).toList());
    }

    @Test
    void zeroInitialisedCountersExistAtZero() {
        MetricsConfig.registerZeroCounters(registry);

        assertThat(registry.find(MetricNames.LIMIT_FAIL_OPEN).tag("component", FailOpenCounter.LIMITER).counter().count()).isZero();
        assertThat(registry.find(MetricNames.LIMIT_FAIL_OPEN).tag("component", FailOpenCounter.BUDGET).counter().count()).isZero();
        assertThat(registry.find(MetricNames.REQUEST_LOG_WRITE_FAILURES).counter().count()).isZero();
        // Registering again (e.g. by the components themselves) returns the same counters.
        FailOpenCounter.of(registry, FailOpenCounter.LIMITER).increment();
        MetricsConfig.registerZeroCounters(registry);
        assertThat(registry.find(MetricNames.LIMIT_FAIL_OPEN).tag("component", FailOpenCounter.LIMITER).counter().count()).isEqualTo(1.0);
    }
}
