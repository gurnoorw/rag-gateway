package dev.raggateway.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FullJitterBackoffTest {

    private static final Duration BASE = Duration.ofMillis(200);
    private static final Duration CAP = Duration.ofSeconds(2);

    private final SplittableRandom seeded = new SplittableRandom(42);
    private final FullJitterBackoff backoff = new FullJitterBackoff(BASE, CAP, CAP, () -> seeded);

    @Test
    void ac_t03_9_waitsFallInsideZeroToCapForEachAttemptOver1000Samples() {
        for (int retry = 1; retry <= 8; retry++) {
            long ceiling = Math.min(CAP.toMillis(), BASE.toMillis() << (retry - 1));
            assertThat(backoff.ceiling(retry)).isEqualTo(Duration.ofMillis(ceiling));
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (int i = 0; i < 1_000; i++) {
                long ms = backoff.delay(retry).toMillis();
                assertThat(ms).isBetween(0L, ceiling).isBetween(0L, CAP.toMillis());
                min = Math.min(min, ms);
                max = Math.max(max, ms);
            }
            // Jitter, not a fixed delay: 1,000 uniform samples cover both ends of the window.
            assertThat(min).as("retry %d min", retry).isLessThan(ceiling / 10);
            assertThat(max).as("retry %d max", retry).isGreaterThan(ceiling * 9 / 10);
        }
    }

    @Test
    void ceilingDoublesFromTheBaseAndStopsAtTheCap() {
        assertThat(backoff.ceiling(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(backoff.ceiling(2)).isEqualTo(Duration.ofMillis(400));
        assertThat(backoff.ceiling(4)).isEqualTo(Duration.ofMillis(1600));
        assertThat(backoff.ceiling(5)).isEqualTo(CAP);
        // No overflow for absurd attempt numbers.
        assertThat(backoff.ceiling(70)).isEqualTo(CAP);
        assertThat(backoff.ceiling(Integer.MAX_VALUE)).isEqualTo(CAP);
        assertThatThrownBy(() -> backoff.ceiling(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryAfterReplacesTheJitterUpToItsCap() {
        var b = new FullJitterBackoff(BASE, CAP, Duration.ofMillis(1500), () -> seeded);

        assertThat(b.delay(1, Optional.of(Duration.ofMillis(700)))).isEqualTo(Duration.ofMillis(700));
        assertThat(b.delay(1, Optional.of(Duration.ofSeconds(30)))).isEqualTo(Duration.ofMillis(1500));
        assertThat(b.delay(1, Optional.of(Duration.ofDays(365_000)))).isEqualTo(Duration.ofMillis(1500));
        assertThat(b.delay(1, Optional.of(Duration.ofSeconds(-1)))).isEqualTo(Duration.ZERO);
        assertThat(b.delay(1, Optional.empty()).toMillis()).isBetween(0L, 200L);
    }

    @Test
    void theWholeRangeIncludingBothEndsIsReachable() {
        RandomGenerator lowest = () -> 0L;
        RandomGenerator highest = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public long nextLong(long bound) {
                return bound - 1;
            }
        };
        assertThat(new FullJitterBackoff(BASE, CAP, CAP, () -> lowest).delay(1)).isEqualTo(Duration.ZERO);
        assertThat(new FullJitterBackoff(BASE, CAP, CAP, () -> highest).delay(1)).isEqualTo(BASE);
    }

    @Test
    void zeroBaseNeverWaits() {
        var b = new FullJitterBackoff(Duration.ZERO, CAP, CAP);
        assertThat(b.delay(3)).isEqualTo(Duration.ZERO);
    }
}
