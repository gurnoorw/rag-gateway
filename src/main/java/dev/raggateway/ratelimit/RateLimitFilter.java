package dev.raggateway.ratelimit;

import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.core.Usage;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.error.RateLimitedException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.FilterChain;
import dev.raggateway.pipeline.FilterOrder;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.StreamSink;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Token rate limit and monthly budget (t02; LLD "Token-bucket rate limiter"; DECISIONS.md C5, C22, C23, D-33).
 * Runs after model selection and the cache (C3), so the reservation is for the resolved model and a cache hit
 * never touches the bucket.
 *
 * <ol>
 *   <li><b>Reservation size:</b> {@code estimateInput(request) + max_tokens}, the most the call can use when
 *       the estimate holds (C5). Larger than the tenant's capacity (its TPM) → 400 {@code invalid_request},
 *       reason {@code too_large}, without calling the limiter or Redis: such a request could never fit.</li>
 *   <li><b>Budget:</b> month-to-date spend (UTC month) must be below the tenant's monthly budget, else 429
 *       {@code budget_exceeded} with no {@code Retry-After} (waiting seconds does not help). Checked, not
 *       reserved (C22): overshoot is bounded by requests in flight x max cost of one request (AC-t02-11).
 *       A ledger that throws is treated as "unknown spend": the check is skipped and counted (D-17).</li>
 *   <li><b>Bucket:</b> {@link RateLimiter#tryAcquire} with the tenant id as bucket key; denied → 429
 *       {@code rate_limited} with {@code Retry-After} ({@link RateLimitedException}).</li>
 *   <li><b>Settlement</b> in {@code finally} after {@code chain.next}, so it runs exactly once whether the
 *       request succeeded, failed, threw, or the client disconnected (C23). Billed usage is
 *       {@link RequestContext#billedUsage()} over every attempt; {@code reserved - billed} goes back to the
 *       bucket when positive. When billed exceeds the reservation (input under-estimate) nothing more is
 *       taken: output is capped by {@code max_tokens}, so the excess is only the estimator's error (D-33).
 *       The cost ({@link CostCalculator}, each attempt priced by its own model) is added to the budget when
 *       it is above 0; it is the same number the request-log row stores.</li>
 * </ol>
 *
 * <p>A refund or budget add that throws is counted as {@code gateway.ratelimit.failopen} (component
 * {@code limiter} or {@code budget}) and logged; it is never retried and never fails the request or masks the
 * exception the request is already ending with. Every rejection is counted as
 * {@code gateway.ratelimit.rejections{tenant, reason}}; the rejected request still gets its log row from the
 * pipeline (status {@code error}, zero tokens, D-33).
 */
@Component
@Order(FilterOrder.RATE_LIMIT)
public class RateLimitFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String TOO_LARGE = "too_large";
    static final String BUDGET_EXCEEDED = "budget_exceeded";
    static final String RATE_LIMITED = "rate_limited";

    private final RateLimiter limiter;
    private final BudgetLedger budget;
    private final TokenEstimator estimator;
    private final CostCalculator costs;
    private final RateLimitProperties properties;
    private final MeterRegistry registry;
    private final Clock clock;

    @Autowired
    public RateLimitFilter(RateLimiter limiter, BudgetLedger budget, TokenEstimator estimator, CostCalculator costs,
                           RateLimitProperties properties, MeterRegistry registry) {
        this(limiter, budget, estimator, costs, properties, registry, Clock.systemUTC());
    }

    /** @param clock decides the budget month (its UTC calendar month) */
    public RateLimitFilter(RateLimiter limiter, BudgetLedger budget, TokenEstimator estimator, CostCalculator costs,
                           RateLimitProperties properties, MeterRegistry registry, Clock clock) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock").withZone(ZoneOffset.UTC);
    }

    @Override
    public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException {
        Tenant tenant = ctx.tenant();
        ChatRequest request = ctx.request();
        BucketSpec spec = properties.bucketFor(tenant.tokensPerMinute());
        long reserved = (long) estimator.estimateInput(request) + request.maxTokens();

        if (reserved > spec.capacity()) {
            reject(tenant, TOO_LARGE);
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "request needs up to " + reserved
                    + " tokens (input estimate + max_tokens), more than this tenant's limit of "
                    + spec.capacity() + " tokens per minute; lower max_tokens or shorten the prompt");
        }
        if (budgetExceeded(tenant)) {
            reject(tenant, BUDGET_EXCEEDED);
            throw new GatewayException(ErrorCode.BUDGET_EXCEEDED, "monthly budget exhausted for this tenant");
        }
        Decision decision = limiter.tryAcquire(tenant.id(), reserved, spec);
        if (!decision.allowed()) {
            reject(tenant, RATE_LIMITED);
            throw new RateLimitedException(decision.retryAfterMs());
        }

        try {
            chain.next(ctx, sink);
        } finally {
            settle(ctx, tenant, spec, reserved);
        }
    }

    private boolean budgetExceeded(Tenant tenant) {
        long budgetMicroUsd = CostCalculator.toMicroUsd(tenant.monthlyBudgetUsd());
        long spent;
        try {
            spent = budget.spentMicroUsd(tenant.id(), YearMonth.now(clock));
        } catch (RuntimeException e) {
            failOpen("budget", tenant, "budget check skipped", e);
            return false;
        }
        return spent >= budgetMicroUsd;
    }

    /** Exactly once per admitted request; never throws. */
    private void settle(RequestContext ctx, Tenant tenant, BucketSpec spec, long reserved) {
        Usage billed = ctx.billedUsage();
        long unused = reserved - billed.total();
        if (unused > 0) {
            try {
                limiter.refund(tenant.id(), unused, spec);
            } catch (RuntimeException e) {
                failOpen("limiter", tenant, "refund of " + unused + " tokens lost", e);
            }
        }
        long cost;
        try {
            cost = costs.costMicroUsd(ctx.attempts());
        } catch (RuntimeException e) {
            failOpen("budget", tenant, "cost not computed", e);
            return;
        }
        if (cost > 0) {
            try {
                budget.addMicroUsd(tenant.id(), YearMonth.now(clock), cost);
            } catch (RuntimeException e) {
                failOpen("budget", tenant, "spend of " + cost + " micro-USD not recorded", e);
            }
        }
    }

    private void reject(Tenant tenant, String reason) {
        registry.counter(MetricNames.LIMIT_REJECTIONS, "tenant", tenant.id(), "reason", reason).increment();
    }

    private void failOpen(String component, Tenant tenant, String what, RuntimeException e) {
        registry.counter(MetricNames.LIMIT_FAIL_OPEN, "component", component).increment();
        log.warn("rate-limit filter, tenant {}: failed open ({}): {}", tenant.id(), what, e.toString());
    }
}
