package dev.raggateway.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache store (LLD, Strategy; a semantic cache would implement the same interface). Adds a short lock for
 * single-flight on a cold key (HLD deep dive 3): {@code SET lock:<key> NX PX ttl}.
 * Every method fails open: a Redis error reads as a miss, a failed put or lock is ignored and counted.
 */
public interface ResponseCache {

    Optional<CachedResponse> get(String key);

    void put(String key, CachedResponse value, Duration ttl);

    /** True if this caller now holds the fill lock for {@code key}. */
    boolean tryLock(String key, Duration ttl);

    void unlock(String key);
}
