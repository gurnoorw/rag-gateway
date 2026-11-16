package dev.raggateway.ratelimit;

import dev.raggateway.metrics.MetricNames;
import dev.raggateway.support.AbstractRedisTest;
import dev.raggateway.support.SharedContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedisBudgetLedgerTest extends AbstractRedisTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double failOpenCount() {
        var counter = registry.find(MetricNames.LIMIT_FAIL_OPEN).tag("component", "budget").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void keyIsBudgetHashTaggedTenantAndUtcMonthWithNoTtl() {
        var ledger = new RedisBudgetLedger(redis, registry);

        ledger.addMicroUsd("acme", NOV, 1_234);

        assertThat(redis.opsForValue().get("budget:{acme}:2026-11")).isEqualTo("1234");
        assertThat(redis.getExpire("budget:{acme}:2026-11")).isEqualTo(-1L); // no TTL: never evicted (D-17)
        assertThat(RedisBudgetLedger.key("acme", YearMonth.of(2027, 1))).isEqualTo("budget:{acme}:2027-01");
    }

    @Test
    void spendIsZeroUntilAddedAndMonthsAndTenantsAreSeparate() {
        var ledger = new RedisBudgetLedger(redis, registry);
        assertThat(ledger.spentMicroUsd("acme", NOV)).isZero();

        ledger.addMicroUsd("acme", NOV, 500);
        ledger.addMicroUsd("acme", NOV, 250);
        ledger.addMicroUsd("acme", NOV.plusMonths(1), 7);
        ledger.addMicroUsd("other", NOV, 9);

        assertThat(ledger.spentMicroUsd("acme", NOV)).isEqualTo(750);
        assertThat(ledger.spentMicroUsd("acme", NOV.plusMonths(1))).isEqualTo(7);
        assertThat(ledger.spentMicroUsd("other", NOV)).isEqualTo(9);
        assertThat(failOpenCount()).isZero();
    }

    @Test
    void nonPositiveAmountsAreIgnored() {
        var ledger = new RedisBudgetLedger(redis, registry);
        ledger.addMicroUsd("acme", NOV, 0);
        ledger.addMicroUsd("acme", NOV, -5);
        assertThat(redis.hasKey("budget:{acme}:2026-11")).isFalse();
    }

    @Test
    void concurrentAddsFromTwoConnectionsAreExact() throws Exception {
        LettuceConnectionFactory second = newConnectionFactory();
        try {
            var ledgerA = new RedisBudgetLedger(redis, registry);
            var ledgerB = new RedisBudgetLedger(new StringRedisTemplate(second), registry);
            List<Future<?>> futures = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 100; i++) {
                    var ledger = i % 2 == 0 ? ledgerA : ledgerB;
                    futures.add(pool.submit(() -> {
                        for (int j = 0; j < 20; j++) ledger.addMicroUsd("acme", NOV, 7);
                    }));
                }
                for (Future<?> f : futures) f.get();
            }
            assertThat(ledgerA.spentMicroUsd("acme", NOV)).isEqualTo(100L * 20 * 7);
        } finally {
            second.destroy();
        }
    }

    @Test
    void redisDownFailsOpenReadAsZeroAddSwallowedBothCounted() {
        // Nothing listens on port 1: connection refused, as with a stopped Redis.
        var config = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50)).build();
        var down = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1), config);
        down.afterPropertiesSet();
        down.start();
        try {
            var ledger = new RedisBudgetLedger(new StringRedisTemplate(down), registry);

            assertThat(ledger.spentMicroUsd("acme", NOV)).isZero();
            assertThatCode(() -> ledger.addMicroUsd("acme", NOV, 100)).doesNotThrowAnyException();

            assertThat(failOpenCount()).isEqualTo(2);
        } finally {
            down.destroy();
        }
    }

    @Test
    void slowRedisFailsOpenAfterTheCommandTimeout() {
        var rd = SharedContainers.redis();
        var config = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50)).build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(rd.getHost(), rd.getMappedPort(6379)), config);
        factory.afterPropertiesSet();
        factory.start();
        try {
            var template = new StringRedisTemplate(factory);
            var ledger = new RedisBudgetLedger(template, registry);
            ledger.addMicroUsd("acme", NOV, 10); // connection established before the pause

            // Every client is paused for 400 ms; the ledger's 50 ms command timeout must fire first.
            redis.execute((RedisCallback<Object>) c -> c.execute("CLIENT",
                    "PAUSE".getBytes(StandardCharsets.UTF_8), "400".getBytes(StandardCharsets.UTF_8),
                    "ALL".getBytes(StandardCharsets.UTF_8)));
            long start = System.nanoTime();
            long spent = ledger.spentMicroUsd("acme", NOV);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(spent).isZero();
            assertThat(elapsedMs).isLessThan(300);
            assertThat(failOpenCount()).isEqualTo(1);
        } finally {
            factory.destroy();
        }
    }
}
