package dev.raggateway.config;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Skip window for every Redis user on the request path: limiter, budget ledger, response cache (D-17, D-46).
 *
 * <p>Failing open after each Redis error is not enough when Redis is black-holed (packets dropped): every call waits
 * the connect timeout, and the benchmark showed requests stuck for tens of seconds (t08 scenario 6b). So after a
 * failure the gate closes for {@code skipWindow}: callers skip Redis and fail open at once. When the window ends,
 * exactly one caller is let through as a probe; the others keep skipping until it reports success (gate opens) or
 * failure (closed for another window). A probe that never reports back is treated as expired after one window, so
 * the gate cannot stay shut forever.
 *
 * <p>Thread-safe and lock-free (two atomics), so it never pins a virtual thread. Callers must report every Redis
 * call they made with {@link #success()} or {@link #failure()}.
 */
public final class RedisHealthGate {

    private static final long HEALTHY = Long.MIN_VALUE;
    private static final long NO_PROBE = Long.MIN_VALUE;

    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final AtomicLong closedUntil = new AtomicLong(HEALTHY);
    private final AtomicLong probeStartedAt = new AtomicLong(NO_PROBE);

    public RedisHealthGate(Duration skipWindow, LongSupplier nanoClock) {
        Objects.requireNonNull(skipWindow, "skipWindow");
        if (skipWindow.isNegative()) throw new IllegalArgumentException("skip window must be >= 0");
        this.windowNanos = skipWindow.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** A gate with a zero window: every call goes to Redis (tests and code built without Spring). */
    public static RedisHealthGate alwaysPass() {
        return new RedisHealthGate(Duration.ZERO, System::nanoTime);
    }

    /** True if the caller may call Redis now; false means skip Redis and fail open. */
    public boolean tryPass() {
        long until = closedUntil.get();
        if (until == HEALTHY) return true;
        long now = nanoClock.getAsLong();
        if (now - until < 0) return false;                       // still inside the skip window
        long probe = probeStartedAt.get();
        if (probe != NO_PROBE && now - probe < windowNanos) return false; // another caller is probing
        return probeStartedAt.compareAndSet(probe, now);          // this caller is the probe
    }

    /** The Redis call worked: open the gate. */
    public void success() {
        if (closedUntil.get() != HEALTHY) closedUntil.set(HEALTHY);
        if (probeStartedAt.get() != NO_PROBE) probeStartedAt.set(NO_PROBE);
    }

    /** The Redis call failed or timed out: skip Redis for one window. */
    public void failure() {
        if (windowNanos == 0) return;
        closedUntil.set(nanoClock.getAsLong() + windowNanos);
        probeStartedAt.set(NO_PROBE);
    }

    /** True while callers are told to skip Redis (diagnostics, tests). */
    public boolean closed() {
        long until = closedUntil.get();
        return until != HEALTHY && nanoClock.getAsLong() - until < 0;
    }
}
