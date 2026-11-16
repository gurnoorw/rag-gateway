package dev.raggateway.provider;

import dev.raggateway.provider.ProviderException.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * First-token and idle-gap timeouts for one streaming provider call (t03, DECISIONS.md C6, D-06, D-07).
 *
 * <p>The adapter's thread blocks in a socket read, so a timeout cannot be "returned"; something else has to
 * unblock it. The watchdog does that by <b>closing the upstream call</b> (cancelling the request before the
 * response headers arrive, closing the response body after), which also closes the connection, so the
 * provider stops generating. The adapter then sees an I/O failure, asks {@link #fired()} and throws
 * {@link #timeout(Throwable)} instead.
 *
 * <p>Two phases, one deadline:
 * <ul>
 *   <li>until {@link #contentStarted()}: the deadline is {@code start + firstTokenTimeout}. Events that are
 *       not content (Claude's {@code message_start} and {@code ping}) do not move it (D-06);</li>
 *   <li>after it: the deadline is {@code last activity + idleTimeout}, moved by every {@link #activity()}.</li>
 * </ul>
 * A token only writes one volatile field; the single shared timer thread re-arms itself lazily when it wakes
 * up before the (moved) deadline, so a long stream costs one timer task per idle period, not one per token.
 * The one move to an earlier deadline (content starts and the idle timeout is shorter than the first-token
 * timeout) reschedules the check.
 *
 * <p>Rejected: Resilience4j {@code TimeLimiter} (runs the call on another thread and interrupts it, which does
 * not unblock a socket read, D-07); a socket read timeout alone (it cannot tell "no content yet" from
 * "content stopped" and Claude's pings would reset it).
 *
 * <p>Thread safety: {@link #guard}, {@link #fired()} and {@link #close()} may race with the timer thread. The
 * closer runs outside the lock (it may do I/O, D-03).
 */
public final class StreamWatchdog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamWatchdog.class);

    /** One daemon timer thread for all streams; its tasks only flip state and close sockets. */
    private static final class SharedTimer {
        static final ScheduledExecutorService INSTANCE = create();

        private static ScheduledExecutorService create() {
            var executor = new ScheduledThreadPoolExecutor(1,
                    Thread.ofPlatform().daemon().name("stream-watchdog").factory());
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }
    }

    private final String provider;
    private final long firstTokenNanos;
    private final long idleNanos;
    private final ScheduledExecutorService timer;
    private final LongSupplier nanoClock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile long deadlineNanos;
    private volatile boolean contentStarted;
    private volatile Kind fired;
    private boolean closed;                    // guarded by lock
    private Runnable closer;                   // guarded by lock
    private ScheduledFuture<?> pending;        // guarded by lock

    StreamWatchdog(String provider, Duration firstTokenTimeout, Duration idleTimeout,
                   ScheduledExecutorService timer, LongSupplier nanoClock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.firstTokenNanos = positive(firstTokenTimeout, "firstTokenTimeout").toNanos();
        this.idleNanos = positive(idleTimeout, "idleTimeout").toNanos();
        this.timer = Objects.requireNonNull(timer, "timer");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * Arms a watchdog for one call; the first-token clock starts now. Use in try-with-resources so it is
     * disarmed on every exit.
     *
     * @param provider          provider name, for the exception message
     * @param firstTokenTimeout time from now to the first content event
     * @param idleTimeout       longest gap between events once content has started
     */
    public static StreamWatchdog start(String provider, Duration firstTokenTimeout, Duration idleTimeout) {
        return new StreamWatchdog(provider, firstTokenTimeout, idleTimeout, SharedTimer.INSTANCE, System::nanoTime)
                .arm();
    }

    StreamWatchdog arm() {
        deadlineNanos = nanoClock.getAsLong() + firstTokenNanos;
        schedule(firstTokenNanos);
        return this;
    }

    /**
     * Sets what to close when the watchdog fires, replacing the previous closer (e.g. "cancel the request"
     * until the headers arrive, then "close the response body"). If it has already fired, the closer runs
     * at once on the calling thread, so a call that started after the deadline is closed too.
     */
    public void guard(Runnable closer) {
        Objects.requireNonNull(closer, "closer");
        lock.lock();
        try {
            if (fired == null) {
                this.closer = closer;
                return;
            }
        } finally {
            lock.unlock();
        }
        runQuietly(closer);
    }

    /** The first content event arrived: from now on only the idle gap is timed. Idempotent. */
    public void contentStarted() {
        if (contentStarted) {
            activity();
            return;
        }
        contentStarted = true;
        deadlineNanos = nanoClock.getAsLong() + idleNanos;
        // The pending check was set for the first-token deadline, which may be later than the idle one.
        if (idleNanos < firstTokenNanos) reschedule(idleNanos);
    }

    /** Something arrived (or was handed on) after content started: moves the idle deadline. */
    public void activity() {
        if (contentStarted) deadlineNanos = nanoClock.getAsLong() + idleNanos;
    }

    /** True once the watchdog has fired; the adapter then reports {@link #timeout} instead of its I/O error. */
    public boolean fired() {
        return fired != null;
    }

    /** The timeout that fired, or null. */
    public Kind firedKind() {
        return fired;
    }

    /**
     * The failure to throw after the watchdog fired: {@link Kind#FIRST_TOKEN_TIMEOUT} or
     * {@link Kind#IDLE_TIMEOUT}, retryable (the router may retry only while no token reached the client).
     */
    public ProviderException timeout(Throwable cause) {
        Kind kind = Objects.requireNonNull(fired, "watchdog has not fired");
        String what = kind == Kind.FIRST_TOKEN_TIMEOUT
                ? "no first content event within " + Duration.ofNanos(firstTokenNanos).toMillis() + " ms"
                : "no stream event for " + Duration.ofNanos(idleNanos).toMillis() + " ms";
        return new ProviderException(kind, provider + ": " + what, true, 0, null, cause);
    }

    /** Disarms the watchdog. Does not close anything. */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            closer = null;
            if (pending != null) pending.cancel(false);
            pending = null;
        } finally {
            lock.unlock();
        }
    }

    private void check() {
        long remaining = deadlineNanos - nanoClock.getAsLong();
        if (remaining > 0) {
            schedule(remaining);
            return;
        }
        Runnable toClose;
        lock.lock();
        try {
            if (closed || fired != null) return;
            fired = contentStarted ? Kind.IDLE_TIMEOUT : Kind.FIRST_TOKEN_TIMEOUT;
            toClose = closer;
            closer = null;
            pending = null;
        } finally {
            lock.unlock();
        }
        log.debug("{} stream watchdog fired: {}", provider, fired);
        if (toClose != null) runQuietly(toClose);
    }

    private void schedule(long delayNanos) {
        lock.lock();
        try {
            if (closed || fired != null) return;
            pending = timer.schedule(this::check, delayNanos, TimeUnit.NANOSECONDS);
        } finally {
            lock.unlock();
        }
    }

    private void reschedule(long delayNanos) {
        lock.lock();
        try {
            if (closed || fired != null) return;
            if (pending != null) pending.cancel(false);
            pending = timer.schedule(this::check, delayNanos, TimeUnit.NANOSECONDS);
        } finally {
            lock.unlock();
        }
    }

    private void runQuietly(Runnable closer) {
        try {
            closer.run();
        } catch (RuntimeException e) {
            // The stream is being abandoned; a failure to close it is not the failure to report.
            log.debug("{} stream watchdog: closing the upstream call failed", provider, e);
        }
    }

    private static Duration positive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) throw new IllegalArgumentException(name + " must be > 0");
        return d;
    }
}
