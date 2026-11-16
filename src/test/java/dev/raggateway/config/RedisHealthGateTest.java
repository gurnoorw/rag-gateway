package dev.raggateway.config;

import dev.raggateway.cache.CachedResponse;
import dev.raggateway.cache.RedisResponseCache;
import dev.raggateway.cache.ResponseCache;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Usage;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.ratelimit.BucketSpec;
import dev.raggateway.ratelimit.FailOpenRateLimiter;
import dev.raggateway.ratelimit.RateLimiter;
import dev.raggateway.ratelimit.RedisBudgetLedger;
import dev.raggateway.support.FakeRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The Redis skip window (D-46): one failure closes it, one probe per window reopens it, users skip while closed. */
class RedisHealthGateTest {

    final AtomicLong now = new AtomicLong(1_000_000_000L);
    final RedisHealthGate gate = new RedisHealthGate(Duration.ofSeconds(1), now::get);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    double skipped(String component) {
        return registry.find(MetricNames.REDIS_SKIPPED).tag("component", component).counter().count();
    }

    @Test
    void closesForOneWindowThenLetsExactlyOneProbeThrough() {
        assertThat(gate.tryPass()).isTrue();
        gate.failure();
        assertThat(gate.tryPass()).as("inside the window").isFalse();
        assertThat(gate.closed()).isTrue();

        now.addAndGet(Duration.ofMillis(1_001).toNanos());
        assertThat(gate.tryPass()).as("first caller after the window probes").isTrue();
        assertThat(gate.tryPass()).as("others keep skipping while it probes").isFalse();

        gate.success();
        assertThat(gate.tryPass()).isTrue();
        assertThat(gate.tryPass()).isTrue();
    }

    @Test
    void aFailedProbeClosesItAgainAndAProbeThatNeverReportsExpires() {
        gate.failure();
        now.addAndGet(Duration.ofMillis(1_001).toNanos());
        assertThat(gate.tryPass()).isTrue();
        gate.failure();
        assertThat(gate.tryPass()).isFalse();

        now.addAndGet(Duration.ofMillis(1_001).toNanos());
        assertThat(gate.tryPass()).as("probe that will never report").isTrue();
        now.addAndGet(Duration.ofMillis(1_001).toNanos());
        assertThat(gate.tryPass()).as("its probe expired after one window: a new probe").isTrue();
    }

    @Test
    void zeroWindowNeverCloses() {
        RedisHealthGate open = RedisHealthGate.alwaysPass();
        open.failure();
        assertThat(open.tryPass()).isTrue();
    }

    @Test
    void limiterFallsBackWithoutCallingRedisWhileClosed() {
        var calls = new AtomicInteger();
        RateLimiter failingRedis = new RateLimiter() {
            @Override
            public dev.raggateway.ratelimit.Decision tryAcquire(String k, long t, BucketSpec s) {
                calls.incrementAndGet();
                throw new RedisConnectionFailureException("black-holed");
            }

            @Override
            public void refund(String k, long t, BucketSpec s) {
                calls.incrementAndGet();
                throw new RedisConnectionFailureException("black-holed");
            }
        };
        var limiter = new FailOpenRateLimiter(failingRedis, new FakeRateLimiter(), registry, now::get, gate);
        var spec = BucketSpec.perMinute(1_000);

        for (int i = 0; i < 10; i++) assertThat(limiter.tryAcquire("t", 10, spec).allowed()).isTrue();
        limiter.refund("t", 5, spec);

        assertThat(calls).as("only the first call reached Redis").hasValue(1);
        assertThat(skipped(RedisSkipped.LIMITER)).isEqualTo(10.0);
    }

    @Test
    void budgetAndCacheSkipRedisWhileClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var ledger = new RedisBudgetLedger(redis, registry, gate);
        var cache = new RedisResponseCache(redis, registry, gate);
        gate.failure();

        assertThat(ledger.spentMicroUsd("t", YearMonth.of(2026, 11))).isZero();
        ledger.addMicroUsd("t", YearMonth.of(2026, 11), 100);
        assertThat(cache.get("k")).isEmpty();
        cache.put("k", new CachedResponse("x", FinishReason.STOP, "m", "p", Usage.ZERO), Duration.ofSeconds(1));
        assertThat(cache.tryLock("k", Duration.ofSeconds(1))).contains(ResponseCache.FAILED_OPEN);

        verifyNoInteractions(redis);
        assertThat(skipped(RedisSkipped.BUDGET)).isEqualTo(2.0);
        assertThat(skipped(RedisSkipped.CACHE)).isEqualTo(3.0);
    }
}
