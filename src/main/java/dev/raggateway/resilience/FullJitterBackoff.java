package dev.raggateway.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Wait before a retry on the same provider (DECISIONS.md D-07, LLD "Circuit breaker and fallback router").
 * Exponential backoff with full jitter: before retry {@code n} (1 for the first retry) the wait is uniform in
 * {@code [0, min(cap, base × 2^(n-1))]}. An upstream {@code Retry-After} replaces the jittered wait, capped at
 * {@code retryAfterCap} (G9), because the provider said when it expects to recover.
 *
 * <p>Why full jitter: when a provider fails, every gateway instance and every request sees the failure at
 * the same moment; a fixed or "equal jitter" backoff makes them all retry together and hit the provider in
 * waves (thundering herd). A uniform wait from zero spreads the retries over the whole window. Rejected:
 * fixed delay (synchronised retries), decorrelated jitter (one more state variable per request for a
 * maximum of one retry).
 */
public final class FullJitterBackoff {

    private final long baseMillis;
    private final long capMillis;
    private final long retryAfterCapMillis;
    private final Supplier<? extends RandomGenerator> random;

    public FullJitterBackoff(Duration base, Duration cap, Duration retryAfterCap) {
        this(base, cap, retryAfterCap, ThreadLocalRandom::current);
    }

    /** @param random source of randomness per call; tests pass a fixed or seeded one */
    public FullJitterBackoff(Duration base, Duration cap, Duration retryAfterCap,
                             Supplier<? extends RandomGenerator> random) {
        this.baseMillis = requireNonNegative(base, "base");
        this.capMillis = requireNonNegative(cap, "cap");
        this.retryAfterCapMillis = requireNonNegative(retryAfterCap, "retryAfterCap");
        this.random = Objects.requireNonNull(random, "random");
    }

    public static FullJitterBackoff of(FallbackProperties p) {
        return new FullJitterBackoff(p.backoffBase(), p.backoffCap(), p.retryAfterCap());
    }

    /** Upper bound of the jittered wait before retry {@code retry}: {@code min(cap, base × 2^(retry-1))}. */
    public Duration ceiling(int retry) {
        if (retry < 1) throw new IllegalArgumentException("retry must be >= 1");
        int shift = retry - 1;
        // base << shift overflows long beyond 62 bits; anything that large is above the cap anyway.
        if (baseMillis == 0) return Duration.ZERO;
        if (shift >= Long.numberOfLeadingZeros(baseMillis) - 1) return Duration.ofMillis(capMillis);
        return Duration.ofMillis(Math.min(capMillis, baseMillis << shift));
    }

    /** Jittered wait before retry {@code retry}: uniform in {@code [0, ceiling(retry)]}, millisecond steps. */
    public Duration delay(int retry) {
        long upper = ceiling(retry).toMillis();
        return Duration.ofMillis(random.get().nextLong(upper + 1));
    }

    /** Wait before retry {@code retry}: the upstream {@code Retry-After} (capped) when present, else jittered. */
    public Duration delay(int retry, Optional<Duration> retryAfter) {
        if (retryAfter.isPresent()) {
            Duration wait = retryAfter.get();
            if (wait.isNegative()) return Duration.ZERO;
            Duration cap = Duration.ofMillis(retryAfterCapMillis);
            return wait.compareTo(cap) > 0 ? cap : Duration.ofMillis(wait.toMillis());
        }
        return delay(retry);
    }

    private static long requireNonNegative(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return d.toMillis();
    }
}
