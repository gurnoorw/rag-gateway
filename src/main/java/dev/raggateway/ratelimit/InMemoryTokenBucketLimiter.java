package dev.raggateway.ratelimit;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-instance {@link RateLimiter}: one {@link TokenBucket} per bucket key in this JVM. Used by
 * {@link FailOpenRateLimiter} while Redis is unavailable (D-17), so each gateway instance enforces the
 * tenant limit on its own: N instances admit up to N x the limit during an outage.
 *
 * <p>Buckets are kept for the life of the process. The key space is the tenant ids (tens), so no eviction
 * is needed; a per-key or per-user limit with an unbounded key space would need an expiring map.
 */
@Component
public final class InMemoryTokenBucketLimiter implements RateLimiter {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    public InMemoryTokenBucketLimiter() {
        this(System::nanoTime);
    }

    /** @param nanoClock monotonic time in nanoseconds; tests pass a controllable one */
    public InMemoryTokenBucketLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    @Override
    public Decision tryAcquire(String bucketKey, long tokens, BucketSpec spec) {
        TokenBucket.checkRequested(tokens, spec);
        long now = nanoClock.getAsLong();
        return bucket(bucketKey, spec, now).tryAcquire(tokens, spec, now);
    }

    @Override
    public void refund(String bucketKey, long tokens, BucketSpec spec) {
        if (tokens < 0) throw new IllegalArgumentException("refund must be >= 0");
        long now = nanoClock.getAsLong();
        TokenBucket bucket = buckets.get(bucketKey);
        // A bucket never created here is full; refunding into it would change nothing.
        if (bucket != null) bucket.refund(tokens, spec, now);
    }

    private TokenBucket bucket(String bucketKey, BucketSpec spec, long now) {
        return buckets.computeIfAbsent(bucketKey, k -> new TokenBucket(spec, now));
    }
}
