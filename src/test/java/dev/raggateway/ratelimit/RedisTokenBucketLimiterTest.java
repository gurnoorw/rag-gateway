package dev.raggateway.ratelimit;

import dev.raggateway.error.RateLimitedException;
import dev.raggateway.support.AbstractRedisTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The Lua token bucket against real Redis. Most tests use a slow refill (1 token per minute), so results
 * do not depend on how fast the test runs (C24); the Retry-After test uses a fast one so it can wait.
 */
class RedisTokenBucketLimiterTest extends AbstractRedisTest {

    /** 10,000 tokens, refilled at 1 per minute: effectively no refill during a test. */
    private static final BucketSpec SLOW_10K = new BucketSpec(10_000, 1);

    private final RedisTokenBucketLimiter limiter = new RedisTokenBucketLimiter(redis);

    @RepeatedTest(5)
    void ac_t02_1_hundredParallelReservationsOfOneThousandAllowExactlyTen() throws Exception {
        List<Boolean> outcomes = race(100, i -> limiter);

        assertThat(outcomes.stream().filter(b -> b).count()).isEqualTo(10);
        assertThat(outcomes.stream().filter(b -> !b).count()).isEqualTo(90);
        assertThat(storedTokens("tenant-a")).isBetween(0.0, 1.0); // at most a fraction refilled during the race
    }

    @RepeatedTest(5)
    void ac_t02_2_twoLimiterInstancesOnSeparateConnectionsStillAllowExactlyTen() throws Exception {
        var factoryA = newConnectionFactory();
        var factoryB = newConnectionFactory();
        try {
            var gatewayA = new RedisTokenBucketLimiter(new StringRedisTemplate(factoryA));
            var gatewayB = new RedisTokenBucketLimiter(new StringRedisTemplate(factoryB));

            List<Boolean> outcomes = race(100, i -> i % 2 == 0 ? gatewayA : gatewayB);

            assertThat(outcomes.stream().filter(b -> b).count()).isEqualTo(10);
        } finally {
            factoryA.destroy();
            factoryB.destroy();
        }
    }

    @Test
    void ac_t02_3_retryAfterRoundsUpToWholeSecondsAndRetryingAfterItSucceeds() throws Exception {
        var fast = new BucketSpec(1_000, 60_000); // 1 token per ms

        assertThat(limiter.tryAcquire("tenant-a", 1_000, fast).allowed()).isTrue();
        Decision denied = limiter.tryAcquire("tenant-a", 1_000, fast);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isBetween(1L, 1_000L);
        long retryAfterSeconds = new RateLimitedException(denied.retryAfterMs()).retryAfterSeconds();
        assertThat(retryAfterSeconds).isEqualTo(1);

        Thread.sleep(TimeUnit.SECONDS.toMillis(retryAfterSeconds)); // the wait is the behaviour under test
        assertThat(limiter.tryAcquire("tenant-a", 1_000, fast).allowed()).isTrue();
    }

    @Test
    void retryAfterIsTheTimeToRefillTheDeficit() {
        var spec = BucketSpec.perMinute(10_000); // 1 token per 6 ms
        limiter.tryAcquire("tenant-a", 10_000, spec);

        Decision denied = limiter.tryAcquire("tenant-a", 1_000, spec);

        assertThat(denied.allowed()).isFalse();
        // 1,000 tokens at 6 ms each, minus whatever refilled between the two calls
        assertThat(denied.retryAfterMs()).isBetween(5_500L, 6_000L);
    }

    @Test
    void refundReturnsUnusedTokens() {
        limiter.tryAcquire("tenant-a", 10_000, SLOW_10K);
        limiter.refund("tenant-a", 700, SLOW_10K);

        assertThat(limiter.tryAcquire("tenant-a", 700, SLOW_10K).allowed()).isTrue();
        assertThat(limiter.tryAcquire("tenant-a", 1, SLOW_10K).allowed()).isFalse();
    }

    @Test
    void refundIsCappedAtCapacity() {
        limiter.tryAcquire("tenant-a", 1_000, SLOW_10K);
        limiter.refund("tenant-a", 50_000, SLOW_10K);

        assertThat(storedTokens("tenant-a")).isEqualTo(10_000.0);
        assertThat(limiter.tryAcquire("tenant-a", 10_000, SLOW_10K).allowed()).isTrue();
        assertThat(limiter.tryAcquire("tenant-a", 1, SLOW_10K).allowed()).isFalse();
    }

    @Test
    void refundToAMissingBucketWritesNothing() {
        limiter.refund("tenant-a", 500, SLOW_10K);

        assertThat(redis.hasKey(RedisTokenBucketLimiter.redisKey("tenant-a"))).isFalse();
    }

    /** Refunds race with acquires on one key; the script is atomic, so no token is lost or created. */
    @Test
    void concurrentAcquireAndRefundConserveTokens() throws Exception {
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(50)) {
            for (int i = 0; i < 200; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    if (!limiter.tryAcquire("tenant-a", 100, SLOW_10K).allowed()) return false;
                    limiter.refund("tenant-a", 100, SLOW_10K);
                    return true;
                }));
            }
            start.countDown();
            for (Future<Boolean> f : results) assertThat(f.get(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(storedTokens("tenant-a")).isEqualTo(10_000.0);
    }

    @Test
    void bucketKeyIsHashTaggedAndExpires() {
        limiter.tryAcquire("tenant-a", 1, BucketSpec.perMinute(10_000));

        String key = RedisTokenBucketLimiter.redisKey("tenant-a");
        assertThat(key).isEqualTo("rl:{tenant-a}");
        assertThat(redis.<String, String>opsForHash().keys(key)).containsExactlyInAnyOrder("tokens", "ts");
        // full refill takes 60 s; the bucket is kept for twice that
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(110_000L, 120_000L);
    }

    @Test
    void timestampIsRedisTimeInMicroseconds() {
        limiter.tryAcquire("tenant-a", 1, SLOW_10K);

        long redisMicros = redis.execute((RedisCallback<Long>) c ->
                c.serverCommands().time(TimeUnit.MICROSECONDS));
        String ts = redis.<String, String>opsForHash().get(RedisTokenBucketLimiter.redisKey("tenant-a"), "ts");
        assertThat(ts).matches("\\d{16}");
        assertThat((double) Long.parseLong(ts)).isCloseTo(redisMicros, within(5_000_000.0));
    }

    @Test
    void requestLargerThanCapacityIsRejectedBeforeRedis() {
        assertThatThrownBy(() -> limiter.tryAcquire("tenant-a", 10_001, SLOW_10K))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redis.hasKey(RedisTokenBucketLimiter.redisKey("tenant-a"))).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKey() {
        assertThat(limiter.tryAcquire("tenant-a", 10_000, SLOW_10K).allowed()).isTrue();
        assertThat(limiter.tryAcquire("tenant-b", 10_000, SLOW_10K).allowed()).isTrue();
        assertThat(limiter.tryAcquire("tenant-a", 1, SLOW_10K).allowed()).isFalse();
    }

    /** {@code n} threads released together, each reserving 1,000 tokens of tenant-a's 10,000. */
    private static List<Boolean> race(int n, IntFunction<RateLimiter> limiterFor) throws Exception {
        var start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                RateLimiter l = limiterFor.apply(i);
                Callable<Boolean> call = () -> {
                    start.await();
                    return l.tryAcquire("tenant-a", 1_000, SLOW_10K).allowed();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> f : futures) outcomes.add(f.get(30, TimeUnit.SECONDS));
            return outcomes;
        }
    }

    private static double storedTokens(String bucketKey) {
        Object v = redis.opsForHash().get(RedisTokenBucketLimiter.redisKey(bucketKey), "tokens");
        return Double.parseDouble((String) v);
    }
}
