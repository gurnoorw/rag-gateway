package dev.raggateway.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.metrics.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.raggateway.config.RedisHealthGate;
import dev.raggateway.config.RedisSkipped;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ResponseCache} in Redis (t05, D-40, LLD "Response cache"), shared by all gateway instances.
 *
 * <ul>
 *   <li><b>Entry:</b> {@code <key>} (from {@link CacheKeyStrategy}, {@code cache:v1:...}) holds the
 *       {@link CachedResponse} as JSON, written with {@code SET key value PX ttl}. The TTL is the only
 *       eviction rule this class sets; Redis {@code volatile-lru} may evict earlier under memory pressure,
 *       which is just a miss.</li>
 *   <li><b>Fill lock:</b> {@code lock:<key>} is taken with {@code SET lock:<key> <token> NX PX lockTtl}, where
 *       the token is a random UUID returned to the caller. {@code unlock(key, token)} runs a compare-and-delete
 *       Lua script, so a holder whose lock expired and was taken by another request never deletes the newer
 *       holder's lock. GET-then-DEL from Java would have exactly that race between the two commands.</li>
 * </ul>
 *
 * <p><b>Fails open</b> (D-17, D-40): any Redis error, including the 50 ms command timeout and a stored value
 * that does not parse, makes {@code get} a miss, drops the {@code put}, makes {@code tryLock} return
 * {@link #FAILED_OPEN}, and makes {@code unlock} a no-op (the lock then expires by its TTL). Each is counted in
 * {@code gateway.cache.errors{operation=get|put|lock|unlock}} and logged at WARN at most once per 10 s; nothing
 * is thrown and nothing is retried.
 *
 * <p>The lock is the single-node Redis pattern, not Redlock: it limits duplicate provider calls, it is not a
 * correctness guarantee. If Redis fails over and loses the lock, the worst case is a second provider call for
 * the same key, which the cache tolerates (the last {@code put} wins with an equal answer).
 */
@Component
public class RedisResponseCache implements ResponseCache {

    /** Prefix of the fill-lock key: {@code lock:<cache key>}. */
    public static final String LOCK_PREFIX = "lock:";

    /** Delete the lock only while it still holds the caller's token; returns 1 if deleted, else 0. */
    static final RedisScript<Long> COMPARE_AND_DELETE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final StringRedisTemplate redis;
    private final RedisHealthGate gate;
    private final Counter skipped;
    private final Counter getErrors;
    private final Counter putErrors;
    private final Counter lockErrors;
    private final Counter unlockErrors;
    private final AtomicLong lastWarnNanos = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

    public RedisResponseCache(StringRedisTemplate redis, MeterRegistry registry) {
        this(redis, registry, RedisHealthGate.alwaysPass());
    }

    /** @param gate skip window shared with the other Redis users (D-46) */
    @Autowired
    public RedisResponseCache(StringRedisTemplate redis, MeterRegistry registry, RedisHealthGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.skipped = RedisSkipped.of(registry, RedisSkipped.CACHE);
        this.redis = Objects.requireNonNull(redis, "redis");
        this.getErrors = errorCounter(registry, "get");
        this.putErrors = errorCounter(registry, "put");
        this.lockErrors = errorCounter(registry, "lock");
        this.unlockErrors = errorCounter(registry, "unlock");
    }

    /** The Redis key of the fill lock for a cache key. */
    public static String lockKey(String key) {
        return LOCK_PREFIX + key;
    }

    @Override
    public Optional<CachedResponse> get(String key) {
        if (!pass()) return Optional.empty();
        String json;
        try {
            json = redis.opsForValue().get(key);
            gate.success();
        } catch (Exception e) { // DataAccessException (down, timeout)
            gate.failure();
            failedOpen(getErrors, "get", e);
            return Optional.empty();
        }
        try {
            return json == null ? Optional.empty() : Optional.of(JSON.readValue(json, CachedResponse.class));
        } catch (Exception e) { // a stored value that does not parse: Redis is fine, the entry is not
            failedOpen(getErrors, "get", e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, CachedResponse value, Duration ttl) {
        Objects.requireNonNull(value, "value");
        long ttlMillis = millis(ttl);
        if (!pass()) return;
        try {
            redis.opsForValue().set(key, JSON.writeValueAsString(value), ttlMillis, TimeUnit.MILLISECONDS);
            gate.success();
        } catch (Exception e) {
            gate.failure();
            failedOpen(putErrors, "put", e);
        }
    }

    @Override
    public Optional<String> tryLock(String key, Duration ttl) {
        long ttlMillis = millis(ttl);
        String token = UUID.randomUUID().toString();
        if (!pass()) return Optional.of(FAILED_OPEN);
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey(key), token, ttlMillis, TimeUnit.MILLISECONDS);
            if (acquired == null) throw new IllegalStateException("SET NX returned no reply");
            gate.success();
            return acquired ? Optional.of(token) : Optional.empty();
        } catch (Exception e) {
            gate.failure();
            failedOpen(lockErrors, "lock", e);
            return Optional.of(FAILED_OPEN);
        }
    }

    @Override
    public void unlock(String key, String token) {
        if (token == null || FAILED_OPEN.equals(token)) return;
        if (!pass()) return; // the lock expires by its TTL
        try {
            redis.execute(COMPARE_AND_DELETE, List.of(lockKey(key)), token);
            gate.success();
        } catch (Exception e) {
            gate.failure();
            failedOpen(unlockErrors, "unlock", e); // the lock expires by its TTL
        }
    }

    /** False while the skip window is closed (D-46): the caller fails open without touching Redis. */
    private boolean pass() {
        if (gate.tryPass()) return true;
        skipped.increment();
        return false;
    }

    private static long millis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be > 0");
        return Math.max(1, ttl.toMillis()); // PX takes whole milliseconds, and 0 is rejected by Redis
    }

    private static Counter errorCounter(MeterRegistry registry, String operation) {
        return Counter.builder(MetricNames.CACHE_ERRORS)
                .description(MetricsConfig.CACHE_ERRORS_DESCRIPTION)
                .tag("operation", operation)
                .register(registry);
    }

    private void failedOpen(Counter counter, String operation, Exception e) {
        counter.increment();
        long now = System.nanoTime();
        long last = lastWarnNanos.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
            // The key is a hash, never request content; still not logged, it is not needed to diagnose Redis.
            log.warn("response cache failed open on {} ({}); further failures in the next 10 s are only counted",
                    operation, e.toString());
        }
    }
}
