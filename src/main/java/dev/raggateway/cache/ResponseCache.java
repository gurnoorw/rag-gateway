package dev.raggateway.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache store (LLD, Strategy; a semantic cache would implement the same interface). Adds a short lock for
 * single-flight on a cold key (HLD deep dive 3): {@code SET lock:<key> <owner> NX PX ttl}.
 *
 * <p><b>Fails open, never slows a request down</b> (D-17, D-40): a Redis error or timeout makes {@link #get}
 * return empty (a miss), {@link #put} do nothing, and {@link #tryLock} return {@link #FAILED_OPEN} ("go ahead
 * and call the provider yourself"): reporting the lock as held would make the caller wait for a fill that can
 * never come. Each
 * error is counted ({@code gateway.cache.errors{operation}}) and never thrown.
 */
public interface ResponseCache {

    /** The stored answer, or empty on a miss or a Redis error. */
    Optional<CachedResponse> get(String key);

    /** Store with a time to live; a Redis error is counted and ignored. */
    void put(String key, CachedResponse value, Duration ttl);

    /** Token returned by {@link #tryLock} when Redis failed: the caller goes ahead; unlocking it does nothing. */
    String FAILED_OPEN = "failed-open";

    /**
     * Try to become the one request that fills {@code key}.
     *
     * @return a lock token (random, unique per call) if this caller now holds the lock, or {@link #FAILED_OPEN}
     *         if Redis failed (fail open, see class doc); empty only when another request holds the lock
     */
    Optional<String> tryLock(String key, Duration ttl);

    /**
     * Release the lock if {@code token} still owns it; no effect if it expired and another caller took it (so a
     * slow holder never deletes someone else's lock), or if the token is {@link #FAILED_OPEN}.
     */
    void unlock(String key, String token);
}
