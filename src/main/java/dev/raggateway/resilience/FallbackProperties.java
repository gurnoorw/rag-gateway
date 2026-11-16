package dev.raggateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Settings of the fallback router ({@code gateway.fallback}, t03; DECISIONS.md C20, D-07). Defaults live
 * here; application.yml only overrides.
 *
 * @param chain         providers tried, in order, after the provider of the selected model has failed
 *                      (C20: Ollama {@code llama3.2:1b}); an entry equal to the primary is skipped
 * @param maxAttempts   attempts per provider, the first call included (retry only before the first token)
 * @param backoffBase   full-jitter base: the wait before retry {@code n} is uniform in
 *                      {@code [0, min(backoffCap, backoffBase × 2^(n-1))]}
 * @param backoffCap    upper bound of the jittered wait
 * @param retryAfterCap an upstream {@code Retry-After} replaces the jittered wait, capped at this (G9)
 * @param breaker       circuit breaker per provider
 */
@ConfigurationProperties("gateway.fallback")
public record FallbackProperties(
        List<ChainEntry> chain,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("200ms") Duration backoffBase,
        @DefaultValue("2s") Duration backoffCap,
        @DefaultValue("2s") Duration retryAfterCap,
        @DefaultValue Breaker breaker) {

    public FallbackProperties {
        chain = chain == null ? List.of() : List.copyOf(chain);
        if (maxAttempts < 1) throw new IllegalArgumentException("gateway.fallback.max-attempts must be >= 1");
        backoffBase = nonNegative(backoffBase, Duration.ofMillis(200), "backoff-base");
        backoffCap = nonNegative(backoffCap, Duration.ofSeconds(2), "backoff-cap");
        retryAfterCap = nonNegative(retryAfterCap, Duration.ofSeconds(2), "retry-after-cap");
        if (breaker == null) breaker = Breaker.defaults();
    }

    /** The defaults above with the given chain (for code that builds the router without Spring). */
    public static FallbackProperties withChain(List<ChainEntry> chain) {
        return new FallbackProperties(chain, 2, null, null, null, null);
    }

    /**
     * One fallback step: a provider and the public model (a {@code gateway.models} key) sent to it.
     *
     * @param provider provider name, e.g. {@code ollama}
     * @param model    public model name served by that provider, e.g. {@code llama3.2:1b}
     */
    public record ChainEntry(String provider, String model) {
        public ChainEntry {
            Objects.requireNonNull(provider, "gateway.fallback.chain[].provider");
            Objects.requireNonNull(model, "gateway.fallback.chain[].model");
        }
    }

    /**
     * Resilience4j circuit breaker per provider (D-07): count-based window, minimum calls equal to the
     * window, one trial call when half-open.
     *
     * @param window               calls in the sliding window (also the minimum before the rate is computed)
     * @param failureRateThreshold percentage of failed calls in the window that opens the breaker
     * @param openDuration         time the breaker stays open before it lets one trial call through
     */
    public record Breaker(@DefaultValue("20") int window,
                          @DefaultValue("50") float failureRateThreshold,
                          @DefaultValue("30s") Duration openDuration) {
        public Breaker {
            if (window < 1) throw new IllegalArgumentException("gateway.fallback.breaker.window must be >= 1");
            if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                throw new IllegalArgumentException("gateway.fallback.breaker.failure-rate-threshold must be in (0, 100]");
            }
            openDuration = nonNegative(openDuration, Duration.ofSeconds(30), "breaker.open-duration");
        }

        public static Breaker defaults() {
            return new Breaker(20, 50f, null);
        }
    }

    private static Duration nonNegative(Duration value, Duration fallback, String name) {
        if (value == null) return fallback;
        if (value.isNegative()) throw new IllegalArgumentException("gateway.fallback." + name + " must not be negative");
        return value;
    }
}
