package dev.raggateway.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link BudgetLedger} in Redis (DECISIONS.md C22, D-17, D-33). One integer per tenant and UTC month under
 * {@code budget:{<tenantId>}:<yyyy-MM>}, in micro-USD, increased with {@code INCRBY} (atomic, exact, no
 * read-modify-write race between gateway instances). The key has no TTL, so {@code volatile-lru} never
 * evicts it and the month's spend survives a Redis restart with AOF; old months stay as a few bytes each.
 * The {@code {tenantId}} hash tag keeps a tenant's keys in one slot if Redis is ever clustered.
 *
 * <p>Fails open (D-17): any Redis error or timeout (50 ms command timeout) is counted as
 * {@code gateway.ratelimit.failopen{component=budget}} and logged at WARN at most once per 10 s. A failed
 * read returns 0, so the budget check is skipped; a failed add is lost (spend is under-counted for that
 * request, the request log still has its cost). Neither is retried and neither throws.
 */
@Component
public class RedisBudgetLedger implements BudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(RedisBudgetLedger.class);
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final StringRedisTemplate redis;
    private final Counter failOpen;
    private final AtomicLong lastWarnNanos = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

    public RedisBudgetLedger(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.failOpen = FailOpenCounter.of(registry, FailOpenCounter.BUDGET);
    }

    /** Redis key of a tenant's spend in one UTC month. */
    public static String key(String tenantId, YearMonth month) {
        return "budget:{" + tenantId + "}:" + month;
    }

    /** Spend so far in micro-USD; 0 when nothing was spent or when Redis failed (the check is then skipped). */
    @Override
    public long spentMicroUsd(String tenantId, YearMonth month) {
        try {
            String value = redis.opsForValue().get(key(tenantId, month));
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            failedOpen("read", tenantId, e);
            return 0;
        }
    }

    /** Adds {@code microUsd} (ignored when not positive). A Redis failure is counted and logged, never thrown. */
    @Override
    public void addMicroUsd(String tenantId, YearMonth month, long microUsd) {
        if (microUsd <= 0) return;
        try {
            redis.opsForValue().increment(key(tenantId, month), microUsd);
        } catch (RuntimeException e) {
            failedOpen("add " + microUsd + " micro-USD to", tenantId, e);
        }
    }

    private void failedOpen(String operation, String tenantId, RuntimeException e) {
        failOpen.increment();
        long now = System.nanoTime();
        long last = lastWarnNanos.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
            log.warn("budget ledger failed open: could not {} the budget of tenant {} ({}); further failures in the"
                    + " next 10 s are only counted", operation, tenantId, e.toString());
        }
    }
}
