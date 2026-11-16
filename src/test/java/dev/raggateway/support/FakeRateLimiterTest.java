package dev.raggateway.support;

import dev.raggateway.ratelimit.BucketSpec;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The t02 test fakes behave as their javadoc says, so filter tests can rely on them. */
class FakeRateLimiterTest {

    @Test
    void takesRefundsCapsAndDenies() {
        var limiter = new FakeRateLimiter().denyRetryAfterMs(1500);
        var spec = BucketSpec.perMinute(1_000);

        assertThat(limiter.tryAcquire("t1", 700, spec).allowed()).isTrue();
        assertThat(limiter.tryAcquire("t1", 400, spec)).satisfies(d -> {
            assertThat(d.allowed()).isFalse();
            assertThat(d.retryAfterMs()).isEqualTo(1500);
        });
        limiter.refund("t1", 5_000, spec);
        assertThat(limiter.tokens("t1")).isEqualTo(1_000);
        assertThat(limiter.tokens("t2")).isNull();
        assertThat(limiter.acquireCalls()).isEqualTo(2);
        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThatThrownBy(() -> limiter.tryAcquire("t1", 1_001, spec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ledgerSumsPerTenantAndMonth() {
        var ledger = new InMemoryBudgetLedger().set("t1", YearMonth.of(2026, 11), 100);
        ledger.addMicroUsd("t1", YearMonth.of(2026, 11), 50);
        ledger.addMicroUsd("t1", YearMonth.of(2026, 12), 7);

        assertThat(ledger.spentMicroUsd("t1", YearMonth.of(2026, 11))).isEqualTo(150);
        assertThat(ledger.spentMicroUsd("t1", YearMonth.of(2026, 12))).isEqualTo(7);
        assertThat(ledger.spentMicroUsd("t2", YearMonth.of(2026, 11))).isZero();
    }
}
