package dev.raggateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code gateway.redis} (D-46).
 *
 * @param skipWindow after a Redis failure, how long every Redis user skips Redis and fails open before one request
 *                   probes again; 0 disables the gate (every call goes to Redis)
 */
@ConfigurationProperties("gateway.redis")
public record RedisGateProperties(@DefaultValue("1s") Duration skipWindow) {
    public RedisGateProperties {
        if (skipWindow == null) skipWindow = Duration.ofSeconds(1);
        if (skipWindow.isNegative()) throw new IllegalArgumentException("gateway.redis.skip-window must be >= 0");
    }
}
