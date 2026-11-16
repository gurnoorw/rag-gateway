package dev.raggateway.support;

import dev.raggateway.ratelimit.BudgetLedger;

import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link BudgetLedger} for filter tests (t02 round prep). Exact micro-USD sums per tenant and
 * month; thread-safe. {@link #set} puts a tenant at the budget edge in one line.
 */
public final class InMemoryBudgetLedger implements BudgetLedger {

    private final Map<String, Long> spent = new ConcurrentHashMap<>();

    @Override
    public long spentMicroUsd(String tenantId, YearMonth month) {
        return spent.getOrDefault(key(tenantId, month), 0L);
    }

    @Override
    public void addMicroUsd(String tenantId, YearMonth month, long microUsd) {
        spent.merge(key(tenantId, month), microUsd, Long::sum);
    }

    /** Set the month's spend directly. */
    public InMemoryBudgetLedger set(String tenantId, YearMonth month, long microUsd) {
        spent.put(key(tenantId, month), microUsd);
        return this;
    }

    private static String key(String tenantId, YearMonth month) {
        return tenantId + ":" + month;
    }
}
