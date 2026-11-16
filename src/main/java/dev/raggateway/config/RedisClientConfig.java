package dev.raggateway.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce client settings for every Redis user (limiter, budget, cache; D-17). The 50 ms command timeout
 * comes from {@code spring.data.redis.timeout} in {@code application.yml}; this class adds what properties
 * cannot set.
 *
 * <p>{@code disconnectedBehavior = REJECT_COMMANDS}: while the connection is down, a command fails at once
 * instead of being queued until reconnect. Lettuce's default queues it, so every request would wait the
 * full command timeout before failing open, and the queue would replay stale reservations once Redis came
 * back. Auto-reconnect stays on, so Redis is used again as soon as it returns.
 *
 * <p>Uses Boot's {@link LettuceClientOptionsBuilderCustomizer}, which edits the options Boot builds (connect
 * timeout, timeout options) instead of replacing them.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    /** One gate for every Redis user on the request path (limiter, budget, cache), D-46. */
    @Bean
    RedisHealthGate redisHealthGate(RedisGateProperties properties) {
        return new RedisHealthGate(properties.skipWindow(), System::nanoTime);
    }

    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhenDisconnected() {
        return RedisClientConfig::applyClientOptions;
    }

    /** The options this gateway sets on top of Boot's; public so Redis tests build clients the same way. */
    public static ClientOptions.Builder applyClientOptions(ClientOptions.Builder builder) {
        return builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true);
    }
}
