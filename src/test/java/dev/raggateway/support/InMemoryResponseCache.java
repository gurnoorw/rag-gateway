package dev.raggateway.support;

import dev.raggateway.cache.CachedResponse;
import dev.raggateway.cache.ResponseCache;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * In-memory {@link ResponseCache} for filter tests (t05 round prep), with the same semantics as the Redis
 * store: TTL on entries and locks, {@code tryLock} exclusive until unlock or expiry, unlock only by the token that holds the
 * lock, and fail-open behaviour when {@link #failing(boolean)} is set (get = miss, put ignored, tryLock =
 * {@code FAILED_OPEN}). Records calls so a test can
 * assert "nothing was stored" (AC-t05-5/6) or "one provider call for 50 requests" (AC-t05-7). Thread-safe.
 */
public final class InMemoryResponseCache implements ResponseCache {

    private record Entry(CachedResponse value, long expiresAtNanos) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private record Lock(String token, long expiresAtNanos) {
    }

    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger lockGrants = new AtomicInteger();
    private volatile boolean failing;

    public InMemoryResponseCache() {
        this(System::nanoTime);
    }

    public InMemoryResponseCache(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    @Override
    public Optional<CachedResponse> get(String key) {
        if (failing) return Optional.empty();
        Entry e = entries.get(key);
        if (e == null || e.expiresAtNanos() <= nanoClock.getAsLong()) return Optional.empty();
        return Optional.of(e.value());
    }

    @Override
    public void put(String key, CachedResponse value, Duration ttl) {
        if (failing) return;
        puts.incrementAndGet();
        entries.put(key, new Entry(value, nanoClock.getAsLong() + ttl.toNanos()));
    }

    @Override
    public Optional<String> tryLock(String key, Duration ttl) {
        if (failing) return Optional.of(FAILED_OPEN);
        long now = nanoClock.getAsLong();
        String token = UUID.randomUUID().toString();
        Lock mine = new Lock(token, now + ttl.toNanos());
        Lock held = locks.compute(key, (k, current) -> current == null || current.expiresAtNanos() <= now ? mine : current);
        if (held != mine) return Optional.empty();
        lockGrants.incrementAndGet();
        return Optional.of(token);
    }

    @Override
    public void unlock(String key, String token) {
        if (failing || FAILED_OPEN.equals(token)) return;
        locks.computeIfPresent(key, (k, current) -> current.token().equals(token) ? null : current);
    }

    /** Simulate Redis being down: every operation fails open. */
    public InMemoryResponseCache failing(boolean failing) {
        this.failing = failing;
        return this;
    }

    public boolean contains(String key) {
        return get(key).isPresent();
    }

    public int size() {
        return entries.size();
    }

    public int puts() {
        return puts.get();
    }

    public int lockGrants() {
        return lockGrants.get();
    }

    /** Remaining TTL of an entry, or null. */
    public Duration ttl(String key) {
        Entry e = entries.get(key);
        return e == null ? null : Duration.ofNanos(e.expiresAtNanos() - nanoClock.getAsLong());
    }
}
