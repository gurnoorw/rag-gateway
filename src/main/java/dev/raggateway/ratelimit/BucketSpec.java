package dev.raggateway.ratelimit;

/**
 * Shape of one token bucket. Production uses {@link #perMinute(long)} (capacity = refill = tenant TPM);
 * tests can choose a slow refill so results do not depend on wall-clock speed (DECISIONS.md C24).
 *
 * @param capacity        maximum tokens in the bucket (burst size)
 * @param refillPerMinute tokens added per minute
 */
public record BucketSpec(long capacity, long refillPerMinute) {
    public BucketSpec {
        if (capacity <= 0 || refillPerMinute <= 0) throw new IllegalArgumentException("capacity and refill must be > 0");
    }

    public static BucketSpec perMinute(long tokensPerMinute) {
        return new BucketSpec(tokensPerMinute, tokensPerMinute);
    }
}
