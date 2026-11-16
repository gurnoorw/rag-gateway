package dev.raggateway.ratelimit;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.core.Usage;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.cost.PriceTable;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.support.AbstractRedisTest;
import dev.raggateway.support.FakeRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t02-11: the budget is checked, not reserved (C22), so requests in flight can overshoot it; the documented
 * bound is "requests in flight x max cost of one request". 20 requests start at the budget edge (1 micro-USD
 * left) against the real Redis ledger; a latch in the fake terminal holds all of them until every one has
 * passed the check, which is the worst case for the bound.
 */
class BudgetOvershootIT extends AbstractRedisTest {

    static final int PARALLEL = 20;
    static final String TENANT = "edge";
    static final String MODEL = "claude-opus-5-5";
    static final BigDecimal BUDGET_USD = new BigDecimal("1.00");
    static final long BUDGET_MICRO = 1_000_000;
    static final int MAX_TOKENS = 500;
    static final YearMonth MONTH = YearMonth.of(2026, 11);

    final CostCalculator costs = new CostCalculator(new PriceTable(Map.of(
            MODEL, new PriceTable.Price(new BigDecimal("4.00"), new BigDecimal("20.00")))));
    final TokenEstimator estimator = new CharRatioTokenEstimator();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    RequestContext ctx() {
        var tenant = new Tenant(TENANT, "Edge", 1_000_000, BUDGET_USD, Set.of());
        var caller = new ApiKeyPrincipal(UUID.randomUUID().toString(), tenant, Set.of(), false);
        var request = new ChatRequest(MODEL, List.of(new Message(Role.USER, "summarise the quarterly report")),
                MAX_TOKENS, null, null, List.of(), false, false, null);
        return new RequestContext(UUID.randomUUID().toString(), System.nanoTime(), caller, request);
    }

    @Test
    void ac_t02_11_twentyParallelAtTheBudgetEdgeOvershootAtMostTwentyMaxCosts() throws Exception {
        var ledger = new RedisBudgetLedger(redis, registry);
        ledger.addMicroUsd(TENANT, MONTH, BUDGET_MICRO - 1);
        var filter = new RateLimitFilter(new FakeRateLimiter(), ledger, estimator, costs,
                new RateLimitProperties(Duration.ofMinutes(1)), registry,
                Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC));

        // Every admitted request uses the most it can: its whole input plus max_tokens of output.
        int input = estimator.estimateInput(ctx().request());
        Usage maxUsage = new Usage(input, MAX_TOKENS);
        long maxCostMicro = costs.costMicroUsd(List.of(attempt(maxUsage)));
        var allInFlight = new CountDownLatch(PARALLEL);
        Terminal holdThenSpend = (ctx, sink) -> {
            allInFlight.countDown();
            await(allInFlight);
            ctx.recordAttempt(attempt(maxUsage));
            sink.onComplete(new Completion(FinishReason.LENGTH, maxUsage, MODEL));
        };
        var pipeline = new DefaultGatewayPipeline(List.of(filter), holdThenSpend, List.of(), registry);

        var admitted = new AtomicInteger();
        List<Future<RequestContext>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < PARALLEL; i++) {
                results.add(pool.submit(() -> {
                    RequestContext ctx = ctx();
                    pipeline.run(ctx, NO_OP);
                    if (ctx.outcome() == RequestOutcome.SUCCESS) admitted.incrementAndGet();
                    return ctx;
                }));
            }
            for (Future<RequestContext> f : results) f.get(30, TimeUnit.SECONDS);
        }

        long spent = ledger.spentMicroUsd(TENANT, MONTH);
        assertThat(admitted).hasValue(PARALLEL); // all passed the check: the worst case the bound covers
        assertThat(spent).isGreaterThan(BUDGET_MICRO); // the overshoot is real ...
        assertThat(spent).isLessThanOrEqualTo(BUDGET_MICRO + PARALLEL * maxCostMicro); // ... and bounded
        assertThat(spent).isEqualTo(BUDGET_MICRO - 1 + PARALLEL * maxCostMicro);

        // Once over, the next request is refused before the provider.
        RequestContext late = ctx();
        pipeline.run(late, NO_OP);
        assertThat(late.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(late.errorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(late.attempts()).isEmpty();
    }

    static Attempt attempt(Usage usage) {
        return new Attempt("claude", MODEL, false, AttemptOutcome.SUCCEEDED, usage, false, 1, 1, null);
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("not all requests reached the terminal");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static final StreamSink NO_OP = new StreamSink() {
        @Override
        public void onToken(String text) {
        }

        @Override
        public void onComplete(Completion completion) {
        }

        @Override
        public void onError(Throwable error) {
        }
    };
}
