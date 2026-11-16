package dev.raggateway.support;

import dev.raggateway.ratelimit.BucketSpec;
import dev.raggateway.ratelimit.Decision;
import dev.raggateway.ratelimit.RateLimiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link RateLimiter} for filter tests (t02 round prep): an exact token count per bucket key,
 * no refill, so outcomes depend only on what the test does. Records acquire and refund calls so a test
 * can assert "the bucket was not touched" (AC-t02-8) or "the full reservation came back" (AC-t02-5).
 * Thread-safe; not a substitute for the real limiters' own tests.
 */
public final class FakeRateLimiter implements RateLimiter {

    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final AtomicInteger acquireCalls = new AtomicInteger();
    private final AtomicInteger refundCalls = new AtomicInteger();
    private volatile long denyRetryAfterMs = 1_000;
    private volatile RuntimeException failure;

    @Override
    public synchronized Decision tryAcquire(String bucketKey, long requested, BucketSpec spec) {
        acquireCalls.incrementAndGet();
        if (failure != null) throw failure;
        if (requested > spec.capacity()) throw new IllegalArgumentException("request larger than bucket");
        long available = tokens.computeIfAbsent(bucketKey, k -> spec.capacity());
        if (available < requested) return Decision.deny(denyRetryAfterMs);
        tokens.put(bucketKey, available - requested);
        return Decision.allow();
    }

    @Override
    public synchronized void refund(String bucketKey, long refunded, BucketSpec spec) {
        refundCalls.incrementAndGet();
        if (failure != null) throw failure;
        long available = tokens.getOrDefault(bucketKey, spec.capacity());
        tokens.put(bucketKey, Math.min(spec.capacity(), available + refunded));
    }

    /** Tokens left in a bucket, or null if it was never touched. */
    public Long tokens(String bucketKey) {
        return tokens.get(bucketKey);
    }

    public int acquireCalls() {
        return acquireCalls.get();
    }

    public int refundCalls() {
        return refundCalls.get();
    }

    /** The wait reported on a denial. */
    public FakeRateLimiter denyRetryAfterMs(long ms) {
        this.denyRetryAfterMs = ms;
        return this;
    }

    /** Make every call throw, e.g. to test a caller's error handling; null to stop failing. */
    public FakeRateLimiter failWith(RuntimeException e) {
        this.failure = e;
        return this;
    }
}
