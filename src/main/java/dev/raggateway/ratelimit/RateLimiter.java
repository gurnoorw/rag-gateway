package dev.raggateway.ratelimit;

/**
 * Token bucket (LLD, Strategy). Implementations (t02): Redis + one Lua script reading Redis {@code TIME}
 * (shared by all instances), an in-memory bucket (fail-open fallback), and a composite that uses Redis and
 * falls back to memory when Redis errors or exceeds its timeout.
 *
 * <p>Differs from the LLD: takes a bucket key and a {@link BucketSpec} instead of tenant id and TPM, so a
 * test can fix the refill rate and a later per-key limit needs no interface change (D-14).
 */
public interface RateLimiter {

    /**
     * Atomically refill, then take {@code tokens} if available.
     *
     * @throws IllegalArgumentException if {@code tokens > spec.capacity()}; the caller maps that case to 400
     *                                  before calling (a request that can never fit)
     */
    Decision tryAcquire(String bucketKey, long tokens, BucketSpec spec);

    /** Return unused tokens, capped at capacity. Best effort: a failed refund is logged and counted. */
    void refund(String bucketKey, long tokens, BucketSpec spec);
}
