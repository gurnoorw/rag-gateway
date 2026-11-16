package dev.raggateway.metrics;

import dev.raggateway.cache.CacheFilter;
import dev.raggateway.core.CacheStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** t05: the cache counters have fixed label sets, so they exist at 0 from startup (D-38) and the panel has data. */
class CacheZeroCountersTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void cacheLookupsAndErrorsExistAtZeroForEveryLabelValue() {
        MetricsConfig.registerZeroCounters(registry);

        for (String result : MetricsConfig.CACHE_LOOKUP_RESULTS) {
            assertThat(registry.find(MetricNames.CACHE_LOOKUPS).tag("result", result).counter().count()).isZero();
        }
        for (String operation : MetricsConfig.CACHE_ERROR_OPERATIONS) {
            assertThat(registry.find(MetricNames.CACHE_ERRORS).tag("operation", operation).counter().count()).isZero();
        }
        assertThat(MetricsConfig.CACHE_ERROR_OPERATIONS).containsExactly("get", "put", "lock", "unlock");
    }

    @Test
    void theLookupResultsAreExactlyTheFilterLabelsOfEveryCacheStatus() {
        assertThat(Arrays.stream(CacheStatus.values()).map(CacheFilter::resultLabel))
                .containsExactlyInAnyOrderElementsOf(MetricsConfig.CACHE_LOOKUP_RESULTS);
    }
}
