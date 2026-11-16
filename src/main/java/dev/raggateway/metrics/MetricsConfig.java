package dev.raggateway.metrics;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.ratelimit.FailOpenCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Request metrics wiring (t04, D-19, D-38): the completion listener, the histogram buckets of the three request
 * timers, and the fixed-label counters registered at 0.
 *
 * <p><b>Buckets.</b> Explicit boundaries instead of Micrometer's default percentile histogram (~70 buckets per
 * series) and no client-side percentiles (a summary's p99 cannot be aggregated across instances). Prometheus
 * computes p50/p95/p99 with {@code histogram_quantile} over {@code _bucket}; the estimate is interpolated
 * within the bucket that holds the quantile, so the edges sit where decisions are made: the 5 s first-token
 * timeout, the 50 ms overhead target. Applied as a {@link MeterFilter} so every timer with that name gets the
 * same edges whoever registers it (series with different edges cannot be summed).
 *
 * <p><b>Zero series.</b> Counters with no label or a fixed label set exist from startup at 0, so {@code rate()}
 * and "absent means healthy" panels are right before the first failure. Series with a {@code tenant} label
 * appear on first use.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    /** {@code gateway.request.duration} and {@code gateway.request.ttft}: 25 ms … 120 s (12 edges, plus +Inf). */
    public static final List<Duration> LATENCY_BUCKETS = List.of(
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120));

    /** {@code gateway.overhead}: 1 ms … 1 s (10 edges, plus +Inf). */
    public static final List<Duration> OVERHEAD_BUCKETS = List.of(
            Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(5), Duration.ofMillis(10),
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1));

    /**
     * {@code gateway.requestlog.write}: 0.5 ms … 100 ms (8 edges, plus +Inf), for the t08 log-insert cost
     * (scenario 8, C7). A single-row insert on a warm pool is sub-millisecond to a few ms; the 2 s Hikari
     * connection timeout lands in +Inf.
     */
    public static final List<Duration> LOG_WRITE_BUCKETS = List.of(
            Duration.ofNanos(500_000), Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(5),
            Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100));

    /** Used by {@code RequestLogWriter} too: Micrometer keeps the first description registered for a name. */
    public static final String REQUEST_LOG_WRITE_FAILURES_DESCRIPTION = "Request-log inserts that failed";

    /** Shared with {@code CacheFilter}, which records one lookup per request through it (t05). */
    public static final String CACHE_LOOKUPS_DESCRIPTION = "Response-cache lookups by result";

    /** For the cache store's own registration of the same counter (t05, D-17). */
    public static final String CACHE_ERRORS_DESCRIPTION = "Response-cache Redis errors, failed open";

    /** {@code result} values of {@code gateway.cache.lookups}: one per {@code CacheStatus}, lower case. */
    public static final List<String> CACHE_LOOKUP_RESULTS = List.of("hit", "miss", "bypass", "not_eligible");

    /** {@code operation} values of {@code gateway.cache.errors} (D-40). */
    public static final List<String> CACHE_ERROR_OPERATIONS = List.of("get", "put", "lock", "unlock");

    @Bean
    RequestMetricsListener requestMetricsListener(MeterRegistry registry, CostCalculator costs,
                                                  GatewayProperties properties) {
        return new RequestMetricsListener(registry, costs, properties.models().keySet());
    }

    /** Applied to the registry by Spring Boot before any meter of these names is registered. */
    @Bean
    MeterFilter requestHistogramBuckets() {
        return histogramBuckets();
    }

    @Bean
    MeterBinder zeroInitialisedCounters() {
        return MetricsConfig::registerZeroCounters;
    }

    /** The bucket filter, also used by unit tests on a plain registry. */
    public static MeterFilter histogramBuckets() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                List<Duration> buckets = switch (id.getName()) {
                    case MetricNames.REQUEST_DURATION, MetricNames.TIME_TO_FIRST_TOKEN -> LATENCY_BUCKETS;
                    case MetricNames.GATEWAY_OVERHEAD -> OVERHEAD_BUCKETS;
                    case MetricNames.REQUEST_LOG_WRITE -> LOG_WRITE_BUCKETS;
                    default -> null;
                };
                if (buckets == null) return config;
                // Timer histograms are in nanoseconds; Prometheus exports the edges in seconds.
                double[] nanos = buckets.stream().mapToDouble(Duration::toNanos).toArray();
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(false)
                        .percentiles()
                        .serviceLevelObjectives(nanos)
                        .build()
                        .merge(config);
            }
        };
    }

    /** Fixed-label counters at 0 (D-38). Registering an existing counter returns it unchanged. */
    static void registerZeroCounters(MeterRegistry registry) {
        FailOpenCounter.of(registry, FailOpenCounter.LIMITER);
        FailOpenCounter.of(registry, FailOpenCounter.BUDGET);
        Counter.builder(MetricNames.REQUEST_LOG_WRITE_FAILURES)
                .description(REQUEST_LOG_WRITE_FAILURES_DESCRIPTION)
                .register(registry);
        for (String result : CACHE_LOOKUP_RESULTS) {
            Counter.builder(MetricNames.CACHE_LOOKUPS).description(CACHE_LOOKUPS_DESCRIPTION)
                    .tag("result", result).register(registry);
        }
        for (String operation : CACHE_ERROR_OPERATIONS) {
            Counter.builder(MetricNames.CACHE_ERRORS).description(CACHE_ERRORS_DESCRIPTION)
                    .tag("operation", operation).register(registry);
        }
    }
}
