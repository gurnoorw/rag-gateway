package dev.raggateway.config;

import dev.raggateway.auth.ApiKeyAuthenticator;
import dev.raggateway.auth.JdbcApiKeyAuthenticator;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.cost.RequestLogWriter;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.GatewayPipeline;
import dev.raggateway.pipeline.RequestCompletionListener;
import dev.raggateway.pipeline.Terminal;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Beans for the plain (Spring-free) core classes of t01. Filters, providers and completion listeners are
 * collected from the context, so a later task adds one by declaring a bean, without touching this class (D-13).
 */
@Configuration(proxyBeanMethods = false)
public class CoreConfig {

    @Bean
    TokenEstimator tokenEstimator() {
        return new CharRatioTokenEstimator();
    }

    @Bean
    ApiKeyAuthenticator apiKeyAuthenticator(JdbcClient jdbc) {
        return new JdbcApiKeyAuthenticator(jdbc);
    }

    @Bean
    RequestLogWriter requestLogWriter(JdbcClient jdbc, MeterRegistry registry, CostCalculator costs) {
        return new RequestLogWriter(jdbc, registry, costs);
    }

    /** The terminal is the t03 fallback router ({@code resilience.ResilienceConfig}). */
    @Bean
    GatewayPipeline gatewayPipeline(ObjectProvider<GatewayFilter> filters, Terminal terminal,
                                    ObjectProvider<RequestCompletionListener> listeners, MeterRegistry registry) {
        return new DefaultGatewayPipeline(filters.orderedStream().toList(), terminal,
                listeners.orderedStream().toList(), registry);
    }
}
