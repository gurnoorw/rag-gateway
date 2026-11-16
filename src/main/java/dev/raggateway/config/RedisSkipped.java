package dev.raggateway.config;

import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;

/** The {@code gateway.redis.skipped{component}} counter, registered the same way everywhere (D-46). */
public final class RedisSkipped {

    public static final String LIMITER = "limiter";
    public static final String BUDGET = "budget";
    public static final String CACHE = "cache";
    public static final List<String> COMPONENTS = List.of(LIMITER, BUDGET, CACHE);

    static final String DESCRIPTION = "Redis calls skipped while the skip window was closed after a Redis failure (D-46)";

    private RedisSkipped() {
    }

    public static Counter of(MeterRegistry registry, String component) {
        return Counter.builder(MetricNames.REDIS_SKIPPED).description(DESCRIPTION).tag("component", component)
                .register(registry);
    }
}
