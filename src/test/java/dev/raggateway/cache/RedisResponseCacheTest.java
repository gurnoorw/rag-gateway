package dev.raggateway.cache;

import dev.raggateway.config.RedisClientConfig;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Usage;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.support.AbstractRedisTest;
import dev.raggateway.support.TestImages;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link RedisResponseCache} against real Redis: entries with TTL (AC-t05-8), the token-owned fill lock
 * (exclusive under concurrency, never released by a stale holder, expires by itself) and fail-open with Redis
 * stopped or paused (own container, see {@link RedisDown}).
 */
class RedisResponseCacheTest extends AbstractRedisTest {

    static final CachedResponse ANSWER = new CachedResponse("Redis is an in-memory data store.", FinishReason.STOP,
            "claude-haiku-5-5", "claude", new Usage(12, 9));
    static final String KEY = "cache:v1:" + "ab".repeat(32);

    private SimpleMeterRegistry registry;
    private RedisResponseCache cache;

    @BeforeEach
    void newCache() {
        registry = new SimpleMeterRegistry();
        cache = new RedisResponseCache(redis, registry);
    }

    @Test
    void putThenGetReturnsTheSameAnswerAndAnAbsentKeyIsAMiss() {
        assertThat(cache.get(KEY)).isEmpty();

        cache.put(KEY, ANSWER, Duration.ofMinutes(1));

        assertThat(cache.get(KEY)).contains(ANSWER);
        assertThat(redis.opsForValue().get(KEY)).startsWith("{").contains("\"finishReason\":\"STOP\"");
        assertThat(errors(registry, "get") + errors(registry, "put")).isZero();
    }

    @Test
    void ac_t05_8_keyTtlInRedisEqualsTheConfiguredTtl() {
        Duration configured = CacheProperties.defaults().ttl();          // 6 h

        cache.put(KEY, ANSWER, configured);

        long ttlSeconds = redis.getExpire(KEY, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isBetween(configured.toSeconds() - 5, configured.toSeconds() + 5);
    }

    @Test
    void entryExpiresAfterItsTtl() {
        cache.put(KEY, ANSWER, Duration.ofMillis(300));
        assertThat(cache.get(KEY)).isPresent();

        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                .until(() -> cache.get(KEY).isEmpty());
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void aValueThatDoesNotParseIsAMissAndCounted() {
        redis.opsForValue().set(KEY, "not json");

        assertThat(cache.get(KEY)).isEmpty();
        assertThat(errors(registry, "get")).isEqualTo(1);
    }

    @Test
    void lockLivesUnderItsOwnKeyWithTheTokenAsValueAndTheLockTtl() {
        Duration lockTtl = CacheProperties.defaults().lockTtl();       // 10 s

        String token = cache.tryLock(KEY, lockTtl).orElseThrow();

        String lockKey = RedisResponseCache.lockKey(KEY);
        assertThat(lockKey).isEqualTo("lock:" + KEY);
        assertThat(redis.opsForValue().get(lockKey)).isEqualTo(token);
        assertThat(redis.getExpire(lockKey, TimeUnit.MILLISECONDS)).isBetween(lockTtl.toMillis() - 1_000, lockTtl.toMillis());
        assertThat(token).isNotEqualTo(ResponseCache.FAILED_OPEN);
        assertThat(cache.get(KEY)).as("the lock is not an entry").isEmpty();
    }

    @Test
    void lockIsExclusiveUnderConcurrencyAcrossTwoGatewayInstances() throws Exception {
        // Two caches on two independent connections, as two gateway instances would have.
        LettuceConnectionFactory secondConnection = newConnectionFactory();
        try {
            var other = new RedisResponseCache(new StringRedisTemplate(secondConnection), new SimpleMeterRegistry());
            int threads = 64;
            var start = new CountDownLatch(1);
            List<Callable<Optional<String>>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                RedisResponseCache instance = i % 2 == 0 ? cache : other;
                calls.add(() -> {
                    start.await();
                    return instance.tryLock(KEY, Duration.ofSeconds(10));
                });
            }
            List<Optional<String>> results = new ArrayList<>();
            try (var pool = Executors.newFixedThreadPool(threads)) {
                List<Future<Optional<String>>> futures = new ArrayList<>();
                for (var c : calls) futures.add(pool.submit(c));
                start.countDown();
                for (var f : futures) results.add(f.get(10, TimeUnit.SECONDS));
            }

            List<String> granted = results.stream().flatMap(Optional::stream).toList();
            assertThat(granted).hasSize(1);
            assertThat(granted.getFirst()).isNotEqualTo(ResponseCache.FAILED_OPEN);
            assertThat(redis.opsForValue().get(RedisResponseCache.lockKey(KEY))).isEqualTo(granted.getFirst());
            assertThat(errors(registry, "lock")).isZero();
        } finally {
            secondConnection.destroy();
        }
    }

    @Test
    void everyGrantGetsAFreshToken() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String token = cache.tryLock(KEY, Duration.ofSeconds(10)).orElseThrow();
            tokens.add(token);
            cache.unlock(KEY, token);
        }
        assertThat(tokens).hasSize(50);
    }

    @Test
    void lockExpiresByItselfSoACrashedHolderBlocksOthersOnlyForTheLockTtl() {
        assertThat(cache.tryLock(KEY, Duration.ofMillis(300))).isPresent();     // holder "crashes": never unlocks
        assertThat(cache.tryLock(KEY, Duration.ofMillis(300))).isEmpty();

        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                .until(() -> cache.tryLock(KEY, Duration.ofSeconds(10)).isPresent());
    }

    @Test
    void staleHolderCannotDeleteTheNewerHoldersLock() {
        String stale = cache.tryLock(KEY, Duration.ofMillis(200)).orElseThrow();
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10))
                .until(() -> !redis.hasKey(RedisResponseCache.lockKey(KEY)));       // the slow holder's lock expired
        String newer = cache.tryLock(KEY, Duration.ofSeconds(10)).orElseThrow();

        cache.unlock(KEY, stale);                                                 // the slow holder finishes late

        assertThat(redis.opsForValue().get(RedisResponseCache.lockKey(KEY))).isEqualTo(newer);
        assertThat(cache.tryLock(KEY, Duration.ofSeconds(10))).as("still held by the newer holder").isEmpty();

        cache.unlock(KEY, newer);

        assertThat(redis.hasKey(RedisResponseCache.lockKey(KEY))).isFalse();
        assertThat(cache.tryLock(KEY, Duration.ofSeconds(10))).isPresent();
        assertThat(errors(registry, "unlock")).isZero();
    }

    @Test
    void unlockingWithFailedOpenOrAnUnknownTokenTouchesNothing() {
        String token = cache.tryLock(KEY, Duration.ofSeconds(10)).orElseThrow();

        cache.unlock(KEY, ResponseCache.FAILED_OPEN);
        cache.unlock(KEY, "not-the-token");
        cache.unlock("cache:v1:other", token);

        assertThat(redis.opsForValue().get(RedisResponseCache.lockKey(KEY))).isEqualTo(token);
    }

    static double errors(MeterRegistry registry, String operation) {
        var counter = registry.find(MetricNames.CACHE_ERRORS).tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    /**
     * Fail open (D-17, D-40) with the production client settings (50 ms command timeout, reject commands while
     * disconnected) against a Redis container of this test's own, which is stopped or paused. Not the shared
     * container: stopping that would break every other Redis test.
     */
    @Nested
    class RedisDown {

        private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(50);
        /** One command timeout plus a reconnect attempt Lettuce may be making; observed values are far lower. */
        private static final Duration BOUND = Duration.ofMillis(600);

        private GenericContainer<?> container;
        private LettuceConnectionFactory factory;
        private StringRedisTemplate ownRedis;
        private SimpleMeterRegistry ownRegistry;
        private RedisResponseCache ownCache;

        @BeforeEach
        void startRedis() {
            container = new GenericContainer<>(TestImages.REDIS).withExposedPorts(6379);
            container.start();
            // The same options Boot builds from application.yml plus RedisClientConfig.
            var options = RedisClientConfig.applyClientOptions(ClientOptions.builder())
                    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                    .timeoutOptions(TimeoutOptions.enabled())
                    .build();
            var client = LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).clientOptions(options).build();
            factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)), client);
            factory.afterPropertiesSet();
            factory.start();
            ownRedis = new StringRedisTemplate(factory);
            ownRegistry = new SimpleMeterRegistry();
            ownCache = new RedisResponseCache(ownRedis, ownRegistry);
        }

        @AfterEach
        void stopRedis() {
            factory.destroy();
            container.stop();
        }

        @Test
        void stoppedRedisIsAMissADroppedPutAndFailedOpenLockAllCountedAndFast() {
            ownCache.put(KEY, ANSWER, Duration.ofMinutes(1));
            String heldToken = ownCache.tryLock("cache:v1:held", Duration.ofSeconds(10)).orElseThrow();
            assertThat(ownCache.get(KEY)).contains(ANSWER);                      // Redis was in use

            container.stop();

            long slowest = 0;
            long t = System.nanoTime();
            Optional<CachedResponse> got = ownCache.get(KEY);
            slowest = Math.max(slowest, System.nanoTime() - t);
            t = System.nanoTime();
            ownCache.put(KEY, ANSWER, Duration.ofMinutes(1));
            slowest = Math.max(slowest, System.nanoTime() - t);
            t = System.nanoTime();
            Optional<String> lock = ownCache.tryLock(KEY, Duration.ofSeconds(10));
            slowest = Math.max(slowest, System.nanoTime() - t);
            t = System.nanoTime();
            ownCache.unlock("cache:v1:held", heldToken);
            slowest = Math.max(slowest, System.nanoTime() - t);

            assertThat(got).as("a miss, not an exception").isEmpty();
            assertThat(lock).as("go ahead, do not wait").contains(ResponseCache.FAILED_OPEN);
            assertThat(errors(ownRegistry, "get")).isEqualTo(1);
            assertThat(errors(ownRegistry, "put")).isEqualTo(1);
            assertThat(errors(ownRegistry, "lock")).isEqualTo(1);
            assertThat(errors(ownRegistry, "unlock")).isEqualTo(1);
            assertThat(Duration.ofNanos(slowest)).isLessThan(BOUND);
        }

        @Test
        void pausedRedisFailsOpenAfterTheCommandTimeoutAndIsUsedAgainWhenItResumes() {
            ownCache.put(KEY, ANSWER, Duration.ofMinutes(1));
            var docker = container.getDockerClient();
            String other = "cache:v1:paused";

            docker.pauseContainerCmd(container.getContainerId()).exec();
            long start = System.nanoTime();
            Optional<CachedResponse> got;
            Optional<String> lock;
            try {
                got = ownCache.get(KEY);
                lock = ownCache.tryLock(other, Duration.ofSeconds(2));
            } finally {
                docker.unpauseContainerCmd(container.getContainerId()).exec();
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(got).isEmpty();
            assertThat(lock).contains(ResponseCache.FAILED_OPEN);
            assertThat(errors(ownRegistry, "get")).isEqualTo(1);
            assertThat(errors(ownRegistry, "lock")).isEqualTo(1);
            assertThat(elapsed).isGreaterThanOrEqualTo(COMMAND_TIMEOUT.multipliedBy(2)).isLessThan(BOUND.multipliedBy(2));

            // Redis is back and answers again.
            assertThat(ownCache.get(KEY)).contains(ANSWER);
            // A timeout is not a "no": the SET NX sent during the pause still ran when Redis resumed, so the
            // lock is held by a token nobody has. Others see the key as locked until its TTL (2 s here,
            // lock-ttl in production) while the FAILED_OPEN caller goes ahead; the TTL is what frees it.
            assertThat(ownCache.tryLock(other, Duration.ofSeconds(2))).isEmpty();
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                    .until(() -> ownCache.tryLock(other, Duration.ofSeconds(2)).isPresent());
            assertThat(errors(ownRegistry, "get") + errors(ownRegistry, "lock")).isEqualTo(2);
        }
    }
}
