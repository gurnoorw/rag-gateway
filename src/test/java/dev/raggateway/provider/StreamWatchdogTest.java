package dev.raggateway.provider;

import dev.raggateway.provider.ProviderException.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The watchdog on its own (t03, D-06, D-07): which deadline applies, that firing runs the closer exactly once,
 * and that the timeout it reports is retryable. Real timer, short timeouts; margins of a few hundred ms.
 */
class StreamWatchdogTest {

    private static final Duration SLACK = Duration.ofMillis(500);

    private final ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    void stopTimer() {
        timer.shutdownNow();
    }

    private StreamWatchdog start(Duration firstToken, Duration idle) {
        return new StreamWatchdog("test", firstToken, idle, timer, System::nanoTime).arm();
    }

    @Test
    void ac_t03_2_firstTokenTimeoutFiresWithinTheConfiguredTimeRunsTheCloserAndIsRetryable() throws Exception {
        Duration firstToken = Duration.ofMillis(200);
        var closed = new CountDownLatch(1);
        var closedAt = new AtomicLong();
        long start = System.nanoTime();
        try (StreamWatchdog watchdog = start(firstToken, Duration.ofSeconds(5))) {
            watchdog.guard(() -> {
                closedAt.set(System.nanoTime());
                closed.countDown();
            });

            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            Duration firedAfter = Duration.ofNanos(closedAt.get() - start);
            assertThat(firedAfter).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(SLACK));
            assertThat(watchdog.fired()).isTrue();
            assertThat(watchdog.firedKind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);

            var cause = new IOException("closed");
            ProviderException e = watchdog.timeout(cause);
            assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
            assertThat(e.retryable()).isTrue();
            assertThat(e.fallbackAllowed()).isTrue();
            assertThat(e.httpStatus()).isZero();
            assertThat(e).hasCause(cause).hasMessageContaining("200 ms");
        }
    }

    @Test
    void activityBeforeContentDoesNotMoveTheFirstTokenDeadline() {
        // Claude's message_start and ping arrive before content; they must not keep a stalled call alive (D-06).
        Duration firstToken = Duration.ofMillis(300);
        long start = System.nanoTime();
        try (StreamWatchdog watchdog = start(firstToken, Duration.ofSeconds(5))) {
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).until(() -> {
                watchdog.activity();
                return watchdog.fired();
            });
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(firstToken.plus(SLACK));
            assertThat(watchdog.firedKind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
        }
    }

    @Test
    void afterContentStartsSteadyActivityKeepsTheStreamAliveAndSilenceFiresTheIdleTimeout() {
        Duration idle = Duration.ofMillis(250);
        try (StreamWatchdog watchdog = start(Duration.ofMillis(250), idle)) {
            watchdog.contentStarted();
            long activeUntil = System.nanoTime() + Duration.ofMillis(800).toNanos();
            // Events every 50 ms for 800 ms: longer than both timeouts, never a gap longer than idle.
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50)).until(() -> {
                watchdog.activity();
                return System.nanoTime() > activeUntil;
            });
            assertThat(watchdog.fired()).isFalse();

            long silentFrom = System.nanoTime();
            await().atMost(Duration.ofSeconds(2)).until(watchdog::fired);
            assertThat(Duration.ofNanos(System.nanoTime() - silentFrom)).isLessThan(idle.plus(SLACK));
            assertThat(watchdog.firedKind()).isEqualTo(Kind.IDLE_TIMEOUT);
            assertThat(watchdog.timeout(null).retryable()).isTrue();
        }
    }

    @Test
    void anIdleTimeoutShorterThanTheFirstTokenTimeoutAppliesAsSoonAsContentStarts() {
        Duration idle = Duration.ofMillis(200);
        try (StreamWatchdog watchdog = start(Duration.ofSeconds(5), idle)) {
            long contentAt = System.nanoTime();
            watchdog.contentStarted();

            await().atMost(Duration.ofSeconds(2)).until(watchdog::fired);
            assertThat(Duration.ofNanos(System.nanoTime() - contentAt)).isLessThan(idle.plus(SLACK));
            assertThat(watchdog.firedKind()).isEqualTo(Kind.IDLE_TIMEOUT);
        }
    }

    @Test
    void closeDisarmsTheWatchdog() {
        var closes = new AtomicInteger();
        StreamWatchdog watchdog = start(Duration.ofMillis(150), Duration.ofMillis(150));
        watchdog.guard(closes::incrementAndGet);
        watchdog.close();

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(1)).until(() -> !watchdog.fired());
        assertThat(closes).hasValue(0);
    }

    @Test
    void onlyTheLatestCloserRunsAndOnlyOnce() throws Exception {
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        try (StreamWatchdog watchdog = start(Duration.ofMillis(150), Duration.ofSeconds(5))) {
            watchdog.guard(first::incrementAndGet);   // e.g. "cancel the request"
            watchdog.guard(second::incrementAndGet);  // replaced by "close the response"

            await().atMost(Duration.ofSeconds(2)).until(() -> second.get() == 1);
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> second.get() == 1);
            assertThat(first).hasValue(0);
        }
    }

    @Test
    void aCloserRegisteredAfterTheWatchdogFiredRunsAtOnce() {
        // The response can arrive just after the deadline; it must still be closed.
        try (StreamWatchdog watchdog = start(Duration.ofMillis(100), Duration.ofSeconds(5))) {
            await().atMost(Duration.ofSeconds(2)).until(watchdog::fired);
            var closes = new AtomicInteger();

            watchdog.guard(closes::incrementAndGet);

            assertThat(closes).hasValue(1);
        }
    }

    @Test
    void aFailingCloserDoesNotStopTheWatchdogReportingTheTimeout() {
        try (StreamWatchdog watchdog = start(Duration.ofMillis(100), Duration.ofSeconds(5))) {
            watchdog.guard(() -> {
                throw new IllegalStateException("already closed");
            });
            await().atMost(Duration.ofSeconds(2)).until(watchdog::fired);
            assertThat(watchdog.timeout(null).kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
        }
    }

    @Test
    void timeoutsMustBePositiveAndTimeoutNeedsAFiredWatchdog() {
        assertThatThrownBy(() -> StreamWatchdog.start("x", Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StreamWatchdog.start("x", Duration.ofSeconds(1), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        try (StreamWatchdog watchdog = start(Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            assertThat(watchdog.fired()).isFalse();
            assertThatThrownBy(() -> watchdog.timeout(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
