package dev.raggateway.ratelimit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.support.FakeRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fail-open composite with a fake primary: which limiter answers, what is counted, what is logged. */
class FailOpenRateLimiterTest {

    private static final BucketSpec SPEC = new BucketSpec(10_000, 1);
    private static final long SECOND = 1_000_000_000L;

    private final FakeRateLimiter primary = new FakeRateLimiter();
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private final InMemoryTokenBucketLimiter local = new InMemoryTokenBucketLimiter(() -> 0);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FailOpenRateLimiter limiter = new FailOpenRateLimiter(primary, local, registry, clock::get);

    private final Logger logger = (Logger) LoggerFactory.getLogger(FailOpenRateLimiter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void usesThePrimaryWhileItWorks() {
        assertThat(limiter.tryAcquire("t", 10_000, SPEC).allowed()).isTrue();
        assertThat(limiter.tryAcquire("t", 1, SPEC).allowed()).isFalse();
        limiter.refund("t", 500, SPEC);

        assertThat(primary.tokens("t")).isEqualTo(500);
        assertThat(primary.refundCalls()).isEqualTo(1);
        assertThat(failOpenCount()).isZero();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void fallsBackToTheLocalBucketWhenThePrimaryFailsAndCountsIt() {
        primary.failWith(new QueryTimeoutException("Redis command timed out"));

        for (int i = 0; i < 10; i++) assertThat(limiter.tryAcquire("t", 1_000, SPEC).allowed()).isTrue();
        Decision denied = limiter.tryAcquire("t", 1_000, SPEC);

        assertThat(denied.allowed()).isFalse();              // the local bucket enforces the same limit
        assertThat(denied.retryAfterMs()).isPositive();
        assertThat(failOpenCount()).isEqualTo(11);
    }

    @Test
    void refundGoesToTheLocalBucketWhenThePrimaryFails() {
        primary.failWith(new RedisConnectionFailureException("connection refused"));
        limiter.tryAcquire("t", 10_000, SPEC);

        limiter.refund("t", 700, SPEC);

        assertThat(limiter.tryAcquire("t", 700, SPEC).allowed()).isTrue();
        assertThat(limiter.tryAcquire("t", 1, SPEC).allowed()).isFalse();
        assertThat(failOpenCount()).isEqualTo(4);
    }

    @Test
    void refundNeverThrowsEvenWhenBothLimitersFail() {
        RateLimiter broken = new FakeRateLimiter().failWith(new IllegalStateException("local broken"));
        var bothBroken = new FailOpenRateLimiter(
                new FakeRateLimiter().failWith(new RedisConnectionFailureException("down")), broken, registry, clock::get);

        bothBroken.refund("t", 100, SPEC);

        assertThat(failOpenCount()).isEqualTo(1);
    }

    @Test
    void requestLargerThanCapacityIsTheCallersErrorAndDoesNotFallBack() {
        assertThatThrownBy(() -> limiter.tryAcquire("t", 10_001, SPEC)).isInstanceOf(IllegalArgumentException.class);

        assertThat(primary.acquireCalls()).isZero();
        assertThat(failOpenCount()).isZero();
    }

    @Test
    void warnsAtMostOncePerTenSeconds() {
        primary.failWith(new RedisConnectionFailureException("down"));

        limiter.tryAcquire("t", 1, SPEC);                    // first failure: WARN
        clock.addAndGet(5 * SECOND);
        limiter.tryAcquire("t", 1, SPEC);                    // 5 s later: counted, not logged
        limiter.refund("t", 1, SPEC);
        clock.addAndGet(5 * SECOND);
        limiter.tryAcquire("t", 1, SPEC);                    // 10 s after the first WARN: WARN again

        assertThat(failOpenCount()).isEqualTo(4);
        assertThat(logs.list).hasSize(2).allSatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("in-memory bucket");
        });
    }

    @Test
    void counterIsRegisteredWithOnlyTheComponentLabel() {
        var counter = registry.get(MetricNames.LIMIT_FAIL_OPEN).counter();

        assertThat(counter.getId().getTags()).extracting(t -> t.getKey() + "=" + t.getValue())
                .containsExactly("component=limiter");
        assertThat(MetricNames.ALLOWED_LABELS).contains("component");
    }

    private double failOpenCount() {
        return registry.get(MetricNames.LIMIT_FAIL_OPEN).tag("component", "limiter").counter().count();
    }
}
