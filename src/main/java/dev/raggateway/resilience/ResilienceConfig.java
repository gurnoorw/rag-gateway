package dev.raggateway.resilience;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resilience4j registries and the fallback router (t03; DECISIONS.md C6, D-07). Built programmatically from
 * {@link FallbackProperties}: no Resilience4j Spring Boot starter, no annotations.
 *
 * <ul>
 *   <li>One {@code CircuitBreaker} per provider, created at startup for every {@link LlmProvider} bean so its
 *       state is exported from the first scrape: {@code resilience4j_circuitbreaker_state{name="claude",state=...}}
 *       through {@link TaggedCircuitBreakerMetrics} (AC-t03-10).</li>
 *   <li>One {@code Retry} per provider, same config for all.</li>
 *   <li>The chain is checked at startup: every entry must name a catalog model served by that provider.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    CircuitBreakerRegistry providerCircuitBreakers(FallbackProperties properties, ObjectProvider<LlmProvider> providers,
                                                   MeterRegistry meters) {
        var registry = CircuitBreakerRegistry.of(breakerConfig(properties.breaker(), Clock.systemUTC()));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        providers.orderedStream().forEach(p -> registry.circuitBreaker(p.name()));
        return registry;
    }

    @Bean
    RetryRegistry providerRetries(FallbackProperties properties) {
        return RetryRegistry.of(retryConfig(properties, FullJitterBackoff.of(properties)));
    }

    /** The one {@link Terminal}; t07 decorates it with output validation. */
    @Bean
    Terminal fallbackRouter(FallbackProperties properties, GatewayProperties gateway,
                            ObjectProvider<LlmProvider> providers, CircuitBreakerRegistry providerCircuitBreakers,
                            RetryRegistry providerRetries, TokenEstimator estimator, MeterRegistry meters) {
        List<LlmProvider> adapters = providers.orderedStream().toList();
        for (String warning : checkChain(properties.chain(), gateway.models(), adapters)) log.warn(warning);
        return new FallbackRouter(adapters, properties.chain(), providerCircuitBreakers, providerRetries, estimator,
                meters);
    }

    /**
     * Breaker per provider (D-07): count-based window, minimum calls = window, threshold, open duration, one
     * trial call half-open. Records only retryable provider failures; ignores the rest (non-retryable 4xx
     * including 401/403, client disconnects, adapter bugs), so they neither open nor close it. The predicates
     * are disjoint, so Resilience4j's order of evaluating them does not matter.
     *
     * @param clock Resilience4j measures the open period with it; tests pass a controllable one
     */
    static CircuitBreakerConfig breakerConfig(FallbackProperties.Breaker b, Clock clock) {
        return CircuitBreakerConfig.custom()
                .slidingWindow(b.window(), b.window(), SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(b.failureRateThreshold())
                .waitDurationInOpenState(b.openDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(FallbackRouter::countsAsProviderFailure)
                .ignoreException(t -> !FallbackRouter.countsAsProviderFailure(t))
                .clock(clock)
                .build();
    }

    /**
     * Retry per provider (D-07): {@code maxAttempts} calls in total; retries only a retryable failure before the
     * first token (never an open breaker); waits the upstream {@code Retry-After} (capped) or full jitter.
     */
    static RetryConfig retryConfig(FallbackProperties p, FullJitterBackoff backoff) {
        return RetryConfig.<Object>custom()
                .maxAttempts(p.maxAttempts())
                .retryOnException(FallbackRouter::retryAllowed)
                .intervalBiFunction((retryNumber, outcome) ->
                        backoff.delay(retryNumber, FallbackRouter.retryAfter(outcome)).toMillis())
                .build();
    }

    /**
     * Fails startup for a chain entry whose model is not in the catalog or belongs to another provider; returns
     * a warning for an entry whose provider has no adapter bean (the router skips it).
     */
    static List<String> checkChain(List<ChainEntry> chain, Map<String, ModelProperties> catalog,
                                   List<LlmProvider> adapters) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ChainEntry e : chain) {
            ModelProperties m = catalog.get(e.model());
            if (m == null) {
                errors.add("gateway.fallback.chain: model '" + e.model() + "' is not in gateway.models");
            } else if (!e.provider().equals(m.provider())) {
                errors.add("gateway.fallback.chain: model '" + e.model() + "' belongs to provider '" + m.provider()
                        + "', not '" + e.provider() + "'");
            } else if (adapters.stream().noneMatch(a -> a.name().equals(e.provider()) && a.supports(e.model()))) {
                warnings.add("gateway.fallback.chain: no adapter serves " + e.provider() + "/" + e.model()
                        + "; the router skips this entry");
            }
        }
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        return warnings;
    }
}
