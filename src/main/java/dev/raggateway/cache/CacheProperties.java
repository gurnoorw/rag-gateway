package dev.raggateway.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * Response cache settings ({@code gateway.cache}, t05 round prep). Defaults live here; application.yml only
 * overrides.
 *
 * @param enabled      master switch; off means every request is {@code NOT_ELIGIBLE}
 * @param ttl          time to live of a stored answer (default 6 h, AC-t05-8)
 * @param lockTtl      how long a fill lock lives; also the longest a waiting request polls before calling the
 *                     provider itself, so a crashed lock holder costs waiters at most this (default 10 s)
 * @param pollInterval how often a waiting request checks for the answer or a free lock (default 50 ms)
 */
@ConfigurationProperties("gateway.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("6h") Duration ttl,
        @DefaultValue("10s") Duration lockTtl,
        @DefaultValue("50ms") Duration pollInterval) {

    public CacheProperties {
        ttl = positive(ttl, Duration.ofHours(6), "ttl");
        lockTtl = positive(lockTtl, Duration.ofSeconds(10), "lock-ttl");
        pollInterval = positive(pollInterval, Duration.ofMillis(50), "poll-interval");
    }

    public static CacheProperties defaults() {
        return new CacheProperties(true, null, null, null);
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        Duration d = value == null ? fallback : value;
        Objects.requireNonNull(d);
        if (d.isNegative() || d.isZero()) throw new IllegalArgumentException("gateway.cache." + name + " must be > 0");
        return d;
    }
}
