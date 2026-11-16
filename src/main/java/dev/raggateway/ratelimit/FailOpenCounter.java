package dev.raggateway.ratelimit;

import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The one way to get the {@code gateway.ratelimit.failopen} counter (D-17), so every place that fails open
 * registers it with the same description and the same {@code component} values. Micrometer keeps the first
 * description it sees for a name, so differing descriptions would depend on bean start order.
 */
public final class FailOpenCounter {

    /** Redis token bucket (acquire or refund) failed; the in-memory bucket was used. */
    public static final String LIMITER = "limiter";
    /** Redis budget read or add failed; the check was skipped or the spend not recorded. */
    public static final String BUDGET = "budget";

    static final String DESCRIPTION =
            "Redis rate-limit or budget operations that failed, where the gateway failed open instead (D-17)";

    private FailOpenCounter() {
    }

    /** The counter for one component ({@link #LIMITER} or {@link #BUDGET}). */
    public static Counter of(MeterRegistry registry, String component) {
        return Counter.builder(MetricNames.LIMIT_FAIL_OPEN)
                .description(DESCRIPTION)
                .tag("component", component)
                .register(registry);
    }
}
