package dev.raggateway.ratelimit;

import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The {@link RateLimiter} the gateway uses: Redis first, and the per-instance in-memory bucket when a Redis
 * call fails (connection down, rejected while disconnected, or over the 50 ms command timeout; D-17). Fails
 * open on purpose: a Redis outage degrades the limit to "per instance" instead of failing requests
 * (HLD deep dive 1). While Redis is down, N instances admit up to N x the tenant limit.
 *
 * <p>Every fallback increments {@code gateway.ratelimit.failopen{component=limiter}}; a WARN is logged at
 * most once per 10 s so an outage does not flood the log. A refund that fails on Redis goes to the
 * in-memory bucket and never throws (D-33).
 *
 * <p>Known imprecision, accepted: the two buckets are independent. A reservation taken from memory during
 * an outage and refunded after Redis returns credits the Redis bucket (capped at capacity), and vice versa.
 * The error is at most one reservation per request in flight across the switch. Likewise a command that
 * times out on the client may still run on a slow Redis: that request is then charged in both buckets
 * (over-limiting, never under-limiting, and it refills).
 */
@Component
@Primary
public final class FailOpenRateLimiter implements RateLimiter {

    static final long WARN_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private static final Logger log = LoggerFactory.getLogger(FailOpenRateLimiter.class);

    private final RateLimiter primary;
    private final RateLimiter fallback;
    private final Counter failOpen;
    private final LongSupplier nanoClock;
    private final AtomicLong lastWarnNanos;

    @Autowired
    public FailOpenRateLimiter(RedisTokenBucketLimiter redis, InMemoryTokenBucketLimiter local, MeterRegistry registry) {
        this(redis, local, registry, System::nanoTime);
    }

    /**
     * @param primary   the shared limiter (Redis)
     * @param fallback  the per-instance limiter used when {@code primary} throws
     * @param nanoClock monotonic time, for the WARN rate limit; tests pass a controllable one
     */
    public FailOpenRateLimiter(RateLimiter primary, RateLimiter fallback, MeterRegistry registry, LongSupplier nanoClock) {
        this.primary = primary;
        this.fallback = fallback;
        this.nanoClock = nanoClock;
        this.lastWarnNanos = new AtomicLong(nanoClock.getAsLong() - WARN_INTERVAL_NANOS); // first failure warns
        this.failOpen = FailOpenCounter.of(registry, FailOpenCounter.LIMITER);
    }

    @Override
    public Decision tryAcquire(String bucketKey, long tokens, BucketSpec spec) {
        TokenBucket.checkRequested(tokens, spec); // the caller's error, not Redis's: never falls back
        try {
            return primary.tryAcquire(bucketKey, tokens, spec);
        } catch (RuntimeException e) {
            onFailure("acquire", e);
            return fallback.tryAcquire(bucketKey, tokens, spec);
        }
    }

    @Override
    public void refund(String bucketKey, long tokens, BucketSpec spec) {
        if (tokens < 0) throw new IllegalArgumentException("refund must be >= 0");
        try {
            primary.refund(bucketKey, tokens, spec);
        } catch (RuntimeException e) {
            onFailure("refund", e);
            try {
                fallback.refund(bucketKey, tokens, spec);
            } catch (RuntimeException fallbackError) {
                log.warn("In-memory refund failed for bucket {}: {}", bucketKey, fallbackError.toString());
            }
        }
    }

    private void onFailure(String operation, RuntimeException e) {
        failOpen.increment();
        if (shouldWarn()) {
            log.warn("Redis rate limiter unavailable during {} ({}); using the in-memory bucket. "
                    + "Further failures are counted in {} and logged at most every 10 s.",
                    operation, e.toString(), MetricNames.LIMIT_FAIL_OPEN);
        }
    }

    private boolean shouldWarn() {
        long now = nanoClock.getAsLong();
        long last = lastWarnNanos.get();
        return now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now);
    }
}
