package dev.raggateway.ratelimit;

import dev.raggateway.support.FakeRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporary, test classpath only: the application context needs a {@link RateLimiter} bean for
 * {@link RateLimitFilter}, and the real limiters arrive on the other t02 branch. Component scanning picks this
 * class up in every full-application test on this branch.
 *
 * <p>Delete this file in the t02 end-to-end commit, after rebasing on the limiter branch, whose
 * {@code @Primary} limiter would win over this bean anyway.
 */
@Configuration(proxyBeanMethods = false)
public class PreMergeRateLimiterConfig {

    @Bean
    FakeRateLimiter preMergeRateLimiter() {
        return new FakeRateLimiter();
    }
}
