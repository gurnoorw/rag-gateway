package dev.raggateway.ratelimit;

import dev.raggateway.config.RedisClientConfig;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.support.TestImages;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t02-7 at limiter level: the fail-open limiter with the production client settings (50 ms command
 * timeout, reject commands while disconnected; D-17) against its own Redis container, which is stopped or
 * paused mid-test. Not the shared container: stopping that would break every other Redis test.
 */
class RedisDownLimiterTest {

    private static final BucketSpec SLOW_10K = new BucketSpec(10_000, 1);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(50);

    private GenericContainer<?> container;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;
    private FailOpenRateLimiter limiter;

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
        redis = new StringRedisTemplate(factory);
        registry = new SimpleMeterRegistry();
        limiter = new FailOpenRateLimiter(new RedisTokenBucketLimiter(redis), new InMemoryTokenBucketLimiter(),
                registry, System::nanoTime);
    }

    @AfterEach
    void stopRedis() {
        factory.destroy();
        container.stop();
    }

    @Test
    void ac_t02_7_redisStoppedRequestsStillPassLocalLimitHoldsAndCounterRises() {
        assertThat(limiter.tryAcquire("tenant-a", 1_000, SLOW_10K).allowed()).isTrue();
        assertThat(redis.hasKey(RedisTokenBucketLimiter.redisKey("tenant-a"))).isTrue(); // Redis was in use
        assertThat(failOpenCount()).isZero();

        container.stop();

        long slowestNanos = 0;
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            assertThat(limiter.tryAcquire("tenant-a", 1_000, SLOW_10K).allowed()).as("request %d", i).isTrue();
            slowestNanos = Math.max(slowestNanos, System.nanoTime() - start);
        }
        Decision overLimit = limiter.tryAcquire("tenant-a", 1_000, SLOW_10K);
        limiter.refund("tenant-a", 1_000, SLOW_10K);   // does not throw; goes to the local bucket
        Decision afterRefund = limiter.tryAcquire("tenant-a", 1_000, SLOW_10K);

        assertThat(overLimit.allowed()).isFalse();
        assertThat(overLimit.retryAfterMs()).isPositive();
        assertThat(afterRefund.allowed()).isTrue();
        assertThat(failOpenCount()).isEqualTo(13);
        // Bound: one command timeout plus the reconnect attempt Lettuce may be making; observed values are
        // far lower (the client has seen the connection close and rejects at once).
        assertThat(Duration.ofNanos(slowestNanos)).isLessThan(Duration.ofMillis(600));
    }

    @Test
    void pausedRedisFailsOpenAfterTheCommandTimeoutAndIsUsedAgainWhenItResumes() {
        assertThat(limiter.tryAcquire("tenant-a", 1_000, SLOW_10K).allowed()).isTrue();
        var docker = container.getDockerClient();

        docker.pauseContainerCmd(container.getContainerId()).exec();
        long start = System.nanoTime();
        Decision duringPause;
        try {
            duringPause = limiter.tryAcquire("tenant-a", 1_000, SLOW_10K);
        } finally {
            docker.unpauseContainerCmd(container.getContainerId()).exec();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(duringPause.allowed()).isTrue();          // the local bucket answered
        assertThat(failOpenCount()).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(COMMAND_TIMEOUT).isLessThan(Duration.ofMillis(600));

        // Redis is back and answers again. A timeout is not a "no": the script sent during the pause still
        // ran when Redis resumed, so the shared bucket holds both reservations (10,000 - 2 x 1,000), while
        // the request was admitted by the local bucket. The tenant is charged twice for that one request.
        assertThat(limiter.tryAcquire("tenant-a", 8_000, SLOW_10K).allowed()).isTrue();
        assertThat(limiter.tryAcquire("tenant-a", 1, SLOW_10K).allowed()).isFalse();
        assertThat(failOpenCount()).isEqualTo(1);
    }

    private double failOpenCount() {
        return registry.get(MetricNames.LIMIT_FAIL_OPEN).tag("component", "limiter").counter().count();
    }
}
