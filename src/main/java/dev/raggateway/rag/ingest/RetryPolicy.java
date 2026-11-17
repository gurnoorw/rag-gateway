package dev.raggateway.rag.ingest;


import java.time.Duration;

/**
 * Retry policy of the indexing worker (t11, D-51): how often a failed job is claimed again and when it is dead. Built
 * from {@code gateway.rag.worker.max-attempts}, {@code retry-base} and {@code retry-max} by
 * {@code RagProperties.Worker#retryPolicy()} (folded into {@code RagProperties} in the t11 review, D-52).
 *
 * @param maxAttempts claims before a job is DEAD (default 5: with the defaults below, about 7.5 min of retries,
 *                    enough to ride out an Ollama restart)
 * @param retryBase   backoff after the first failed attempt (default 30 s), doubled after each further one
 * @param retryMax    longest backoff (default 10 min)
 */
public record RetryPolicy(int maxAttempts, Duration retryBase, Duration retryMax) {

    public RetryPolicy {
        retryBase = retryBase == null ? Duration.ofSeconds(30) : retryBase;
        retryMax = retryMax == null ? Duration.ofMinutes(10) : retryMax;
        if (maxAttempts < 1) throw new IllegalArgumentException("gateway.rag.worker.max-attempts must be >= 1");
        if (retryBase.isNegative() || retryBase.isZero()) {
            throw new IllegalArgumentException("gateway.rag.worker.retry-base must be > 0");
        }
        if (retryMax.compareTo(retryBase) < 0) {
            throw new IllegalArgumentException("gateway.rag.worker.retry-max must be >= retry-base");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(5, null, null);
    }

    /**
     * Wait before the next claim of a job whose attempt {@code attempts} (1-based) failed:
     * {@code retryBase × 2^(attempts − 1)}, at most {@code retryMax}. With the defaults: 30 s, 1 min, 2 min, 4 min.
     */
    public Duration backoff(int attempts) {
        if (attempts < 1) throw new IllegalArgumentException("attempts are 1-based");
        int doublings = Math.min(attempts - 1, 30); // 2^30 × any positive base already exceeds any sane cap
        long millis;
        try {
            millis = Math.multiplyExact(retryBase.toMillis(), 1L << doublings);
        } catch (ArithmeticException overflow) {
            return retryMax;
        }
        Duration d = Duration.ofMillis(millis);
        return d.compareTo(retryMax) > 0 ? retryMax : d;
    }
}
