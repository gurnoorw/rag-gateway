package dev.raggateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The in-memory bucket and limiter, driven by a fake monotonic clock (no sleeps). */
class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    /** 10,000 capacity, refills 10,000 per minute = 1 token per 6 ms. */
    private static final BucketSpec TPM_10K = BucketSpec.perMinute(10_000);

    @Test
    void startsFullAndDeniesWhenEmpty() {
        var bucket = new TokenBucket(TPM_10K, 0);

        for (int i = 0; i < 10; i++) assertThat(bucket.tryAcquire(1_000, TPM_10K, 0).allowed()).isTrue();
        Decision denied = bucket.tryAcquire(1_000, TPM_10K, 0);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isEqualTo(6_000); // 1,000 tokens at 10,000 / 60,000 ms
    }

    @Test
    void refillsWithElapsedTimeAndCapsAtCapacity() {
        var bucket = new TokenBucket(TPM_10K, 0);
        bucket.tryAcquire(10_000, TPM_10K, 0);

        assertThat(bucket.available(TPM_10K, 6 * SECOND)).isEqualTo(1_000.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(bucket.tryAcquire(1_000, TPM_10K, 6 * SECOND).allowed()).isTrue();
        assertThat(bucket.available(TPM_10K, 3_600 * SECOND)).isEqualTo(10_000.0);
    }

    @Test
    void retryAfterIsEnoughToSucceed() {
        var bucket = new TokenBucket(TPM_10K, 0);
        bucket.tryAcquire(9_999, TPM_10K, 0);

        Decision denied = bucket.tryAcquire(5_000, TPM_10K, 0);
        assertThat(denied.allowed()).isFalse();
        long waitNanos = denied.retryAfterMs() * 1_000_000;

        assertThat(bucket.tryAcquire(5_000, TPM_10K, waitNanos - 1_000_000).allowed()).isFalse();
        assertThat(bucket.tryAcquire(5_000, TPM_10K, waitNanos).allowed()).isTrue();
    }

    @Test
    void tinyDeficitStillReportsAtLeastOneMillisecond() {
        var fast = new BucketSpec(1_000, 600_000_000); // 10,000 tokens per ms
        var bucket = new TokenBucket(fast, 0);
        bucket.tryAcquire(1_000, fast, 0);

        Decision denied = bucket.tryAcquire(1, fast, 0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isEqualTo(1);
    }

    @Test
    void refundIsCappedAtCapacity() {
        var bucket = new TokenBucket(TPM_10K, 0);
        bucket.tryAcquire(1_000, TPM_10K, 0);
        bucket.refund(700, TPM_10K, 0);
        assertThat(bucket.available(TPM_10K, 0)).isEqualTo(9_700.0);

        bucket.refund(50_000, TPM_10K, 0);
        assertThat(bucket.available(TPM_10K, 0)).isEqualTo(10_000.0);
    }

    @Test
    void clockGoingBackwardsGrantsNothing() {
        var bucket = new TokenBucket(TPM_10K, 10 * SECOND);
        bucket.tryAcquire(10_000, TPM_10K, 10 * SECOND);

        assertThat(bucket.available(TPM_10K, 5 * SECOND)).isZero();
        assertThat(bucket.available(TPM_10K, 10 * SECOND)).isZero();
    }

    @Test
    void rejectsRequestsLargerThanCapacityOrNegative() {
        var bucket = new TokenBucket(TPM_10K, 0);
        assertThatThrownBy(() -> bucket.tryAcquire(10_001, TPM_10K, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bucket.tryAcquire(-1, TPM_10K, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bucket.refund(-1, TPM_10K, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(bucket.available(TPM_10K, 0)).isEqualTo(10_000.0);
    }

    @Test
    void limiterKeepsOneBucketPerKey() {
        var clock = new AtomicLong();
        var limiter = new InMemoryTokenBucketLimiter(clock::get);

        assertThat(limiter.tryAcquire("a", 10_000, TPM_10K).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", 1, TPM_10K).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 10_000, TPM_10K).allowed()).isTrue();

        limiter.refund("a", 3_000, TPM_10K);
        assertThat(limiter.tryAcquire("a", 3_000, TPM_10K).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", 1, TPM_10K).allowed()).isFalse();

        clock.set(6 * SECOND);
        assertThat(limiter.tryAcquire("a", 1_000, TPM_10K).allowed()).isTrue();
    }

    @Test
    void limiterRejectsTooLargeWithoutCreatingABucket() {
        var limiter = new InMemoryTokenBucketLimiter(() -> 0);
        assertThatThrownBy(() -> limiter.tryAcquire("a", 10_001, TPM_10K)).isInstanceOf(IllegalArgumentException.class);
        // a refund into a bucket that was never created is a no-op, and the bucket is still full
        limiter.refund("a", 5, TPM_10K);
        assertThat(limiter.tryAcquire("a", 10_000, TPM_10K).allowed()).isTrue();
    }

    /** 100 threads race for 10 x 1,000 tokens on one key: exactly 10 win (same shape as AC-t02-1). */
    @Test
    void limiterIsAtomicUnderConcurrency() throws Exception {
        var limiter = new InMemoryTokenBucketLimiter(() -> 0); // frozen clock: no refill during the race
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(100)) {
            for (int i = 0; i < 100; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return limiter.tryAcquire("t", 1_000, TPM_10K).allowed();
                }));
            }
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> f : results) if (f.get(10, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(10);
        }
    }
}
