package dev.raggateway.api;

import dev.raggateway.auth.ApiKeyAuthInterceptor;
import dev.raggateway.auth.CallerArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Wires the HTTP edge: request id first, then API-key auth on {@code /v1/**}, then caller injection. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyAuthInterceptor authInterceptor;
    private final CallerArgumentResolver callerResolver;

    public WebConfig(ApiKeyAuthInterceptor authInterceptor, CallerArgumentResolver callerResolver) {
        this.authInterceptor = authInterceptor;
        this.callerResolver = callerResolver;
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * Load shedding (D-46), right after the request id so a 503 carries it, before authentication.
     *
     * @param maxInFlight {@code gateway.max-in-flight}: concurrent {@code /v1} requests per instance; 0 disables it
     */
    @Bean
    FilterRegistrationBean<InFlightLimitFilter> inFlightLimitFilter(
            @Value("${gateway.max-in-flight:1500}") int maxInFlight, MeterRegistry registry, ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new InFlightLimitFilter(maxInFlight, registry, json));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/v1/*");
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callerResolver);
    }
}
