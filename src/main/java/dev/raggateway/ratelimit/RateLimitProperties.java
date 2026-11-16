package dev.raggateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the rate-limit filter ({@code gateway.rate-limit}, t02). Defaults live here; application.yml
 * only overrides.
 *
 * <p>The tenant's {@code tokens_per_minute} is the bucket capacity (the largest burst). {@code refillPeriod}
 * is how long an empty bucket takes to refill completely: 1 minute in production, which makes the long-run
 * rate exactly the tenant's TPM ({@link BucketSpec#perMinute}). A test of {@code Retry-After} at the HTTP
 * level shortens it so the wait is about a second without changing the capacity (DECISIONS.md C24).
 *
 * @param refillPeriod time for an empty bucket to refill to capacity; at least 1 ms
 */
@ConfigurationProperties("gateway.rate-limit")
public record RateLimitProperties(@DefaultValue("1m") Duration refillPeriod) {

    private static final long MILLIS_PER_MINUTE = 60_000;

    public RateLimitProperties {
        if (refillPeriod == null) refillPeriod = Duration.ofMinutes(1);
        if (refillPeriod.toMillis() < 1) throw new IllegalArgumentException("gateway.rate-limit.refill-period must be >= 1ms");
    }

    /** Bucket of a tenant with the given TPM: capacity = TPM, refilled over {@link #refillPeriod}. */
    public BucketSpec bucketFor(long tokensPerMinute) {
        long refillPerMinute = Math.max(1, Math.round((double) tokensPerMinute * MILLIS_PER_MINUTE / refillPeriod.toMillis()));
        return new BucketSpec(tokensPerMinute, refillPerMinute);
    }
}
