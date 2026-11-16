package dev.raggateway.ratelimit;

import java.time.YearMonth;

/**
 * Monthly spend per tenant in micro-USD (integers, so Redis {@code INCRBY} is exact). Checked before the
 * call, added after it with the actual cost (DECISIONS.md C22): overspend is bounded by
 * (requests in flight for the tenant) x (max cost of one request). Months are UTC calendar months.
 */
public interface BudgetLedger {

    long spentMicroUsd(String tenantId, YearMonth month);

    void addMicroUsd(String tenantId, YearMonth month, long microUsd);
}
