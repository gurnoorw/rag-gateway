package dev.raggateway.ratelimit;

/**
 * One in-memory token bucket (LLD "Step 1"). The same arithmetic as {@code acquire.lua}: refill by elapsed
 * time, capped at capacity, then take the request or report how long until it fits.
 *
 * <p>Differs from the LLD: the {@link BucketSpec} is passed on every call instead of fixed at construction,
 * matching the {@link RateLimiter} contract (D-14), and time is a monotonic nanosecond reading supplied by
 * the caller, so tests drive it without sleeping. Thread-safe: every method is {@code synchronized}; a
 * bucket is touched by one tenant's requests only, so the lock is not contended across tenants.
 */
public final class TokenBucket {

    private static final double NANOS_PER_MINUTE = 60e9;
    private static final double MILLIS_PER_MINUTE = 60_000.0;

    private double tokens;
    private long lastRefillNanos;

    /** A full bucket. */
    public TokenBucket(BucketSpec spec, long nowNanos) {
        this.tokens = spec.capacity();
        this.lastRefillNanos = nowNanos;
    }

    /**
     * Refill, then take {@code requested} tokens if they are there.
     *
     * @return allowed, or denied with the milliseconds until {@code requested} tokens will be available (≥ 1)
     * @throws IllegalArgumentException if {@code requested} is negative or larger than the capacity
     */
    public synchronized Decision tryAcquire(long requested, BucketSpec spec, long nowNanos) {
        checkRequested(requested, spec);
        refill(spec, nowNanos);
        if (tokens >= requested) {
            tokens -= requested;
            return Decision.allow();
        }
        long waitMs = (long) Math.ceil((requested - tokens) * MILLIS_PER_MINUTE / spec.refillPerMinute());
        return Decision.deny(Math.max(1, waitMs));
    }

    /** Refill, then add {@code refunded} tokens, capped at capacity. Negative amounts are rejected. */
    public synchronized void refund(long refunded, BucketSpec spec, long nowNanos) {
        if (refunded < 0) throw new IllegalArgumentException("refund must be >= 0");
        refill(spec, nowNanos);
        tokens = Math.min(spec.capacity(), tokens + refunded);
    }

    /** Tokens available now (after refill). For tests and diagnostics. */
    public synchronized double available(BucketSpec spec, long nowNanos) {
        refill(spec, nowNanos);
        return tokens;
    }

    static void checkRequested(long requested, BucketSpec spec) {
        if (requested < 0) throw new IllegalArgumentException("requested tokens must be >= 0");
        if (requested > spec.capacity()) throw new IllegalArgumentException("request larger than bucket");
    }

    private void refill(BucketSpec spec, long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastRefillNanos);
        tokens = Math.min(spec.capacity(), tokens + (double) elapsed * spec.refillPerMinute() / NANOS_PER_MINUTE);
        // Never move backwards: a stale reading from another thread must not re-grant elapsed time.
        lastRefillNanos = Math.max(lastRefillNanos, nowNanos);
    }
}
