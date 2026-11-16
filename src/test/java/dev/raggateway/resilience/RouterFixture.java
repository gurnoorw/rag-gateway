package dev.raggateway.resilience;

import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * Builds a {@link FallbackRouter} for tests, the way {@link ResilienceConfig} does, with fake time: the breaker
 * reads a {@link MutableClock}, and the jittered backoff draws 0 so retries do not sleep. Public so tests of other
 * packages (pipeline, rate limit) run against the real terminal.
 */
public final class RouterFixture {

    /** Draws 0 from every bounded call, so a full-jitter wait is always 0 ms. */
    private static final RandomGenerator ZERO = () -> 0L;

    public final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
    public final MeterRegistry meters = new SimpleMeterRegistry();
    public final CircuitBreakerRegistry breakers;
    public final RetryRegistry retries;
    public final FallbackRouter router;

    public RouterFixture(List<? extends LlmProvider> providers, List<ChainEntry> chain) {
        this(providers, FallbackProperties.withChain(chain));
    }

    public RouterFixture(List<? extends LlmProvider> providers, FallbackProperties properties) {
        var backoff = new FullJitterBackoff(properties.backoffBase(), properties.backoffCap(),
                properties.retryAfterCap(), () -> ZERO);
        this.breakers = CircuitBreakerRegistry.of(ResilienceConfig.breakerConfig(properties.breaker(), clock));
        this.retries = RetryRegistry.of(ResilienceConfig.retryConfig(properties, backoff));
        this.router = new FallbackRouter(List.copyOf(providers), properties.chain(), breakers, retries,
                new CharRatioTokenEstimator(), meters);
    }

    /** A router over the given providers with no fallback chain (the t01 behaviour plus retry). */
    public static FallbackRouter single(LlmProvider provider) {
        return new RouterFixture(List.of(provider), List.of()).router;
    }

    public CircuitBreaker breaker(String provider) {
        return breakers.circuitBreaker(provider);
    }

    public double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    /** A {@link Clock} the test moves by hand. */
    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        public MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        public void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
