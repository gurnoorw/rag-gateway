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
import dev.raggateway.core.Usage;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.cost.PriceTable;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.error.RateLimitedException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.pipeline.FilterChain;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.resilience.RouterFixture;
import dev.raggateway.support.FakeLlmProvider;
import dev.raggateway.support.FakeRateLimiter;
import dev.raggateway.support.InMemoryBudgetLedger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RateLimitFilter} with the shared {@link FakeRateLimiter} (exact counts, no refill) and an in-memory
 * ledger. The bucket key is the tenant id, so {@code limiter.tokens(TENANT)} is the tenant's bucket.
 * The request text is "x": its input estimate is 5 tokens (1 + 4 overhead), so a reservation of R tokens
 * is a request with {@code max_tokens = R - 5}.
 */
class RateLimitFilterTest {

    static final String TENANT = "t1";
    static final String OPUS = "claude-opus-5-5";
    static final String HAIKU = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final long CAPACITY = 1_000;
    static final int INPUT_ESTIMATE = 5;
    static final YearMonth NOV = YearMonth.of(2026, 11);
    static final BigDecimal BUDGET_USD = new BigDecimal("10.00");
    static final long BUDGET_MICRO = 10_000_000;

    static final CostCalculator COSTS = new CostCalculator(new PriceTable(Map.of(
            OPUS, new PriceTable.Price(new BigDecimal("4.00"), new BigDecimal("20.00")),
            HAIKU, new PriceTable.Price(new BigDecimal("0.10"), new BigDecimal("0.50")),
            LLAMA, new PriceTable.Price(BigDecimal.ZERO, BigDecimal.ZERO))));

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final FakeRateLimiter limiter = new FakeRateLimiter();
    final RecordingLedger ledger = new RecordingLedger();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
    final RateLimitFilter filter = filter(ledger, clock);

    RateLimitFilter filter(BudgetLedger budget, Clock clock) {
        return new RateLimitFilter(limiter, budget, new CharRatioTokenEstimator(), COSTS,
                new RateLimitProperties(Duration.ofMinutes(1)), registry, clock);
    }

    // ---- fixtures ----

    static RequestContext ctx(String model, long reservation, boolean stream) {
        var tenant = new Tenant(TENANT, "Tenant 1", CAPACITY, BUDGET_USD, Set.of());
        var caller = new ApiKeyPrincipal(UUID.randomUUID().toString(), tenant, Set.of(), false);
        var request = new ChatRequest(model, List.of(new Message(Role.USER, "x")),
                (int) (reservation - INPUT_ESTIMATE), null, null, List.of(), stream, false, null);
        return new RequestContext(UUID.randomUUID().toString(), System.nanoTime(), caller, request);
    }

    static RequestContext ctx(long reservation) {
        return ctx(OPUS, reservation, false);
    }

    /** Terminal that records the given attempts and completes (last attempt succeeded) or errors. */
    static Terminal recording(Attempt... attempts) {
        return (ctx, sink) -> {
            for (Attempt a : attempts) ctx.recordAttempt(a);
            Attempt last = attempts[attempts.length - 1];
            if (last.outcome() == AttemptOutcome.SUCCEEDED) {
                sink.onToken("ok");
                sink.onComplete(new Completion(FinishReason.STOP, last.usage(), last.model()));
            } else {
                sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "provider failed"));
            }
        };
    }

    static Attempt succeeded(String model, int in, int out) {
        return new Attempt("claude", model, false, AttemptOutcome.SUCCEEDED, new Usage(in, out), false, 1, 1, null);
    }

    static Attempt failed(String model, Usage usage, boolean estimated) {
        return new Attempt("claude", model, false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, usage, estimated, 1, -1,
                "HTTP_STATUS");
    }

    /** Client sink like the edge's; fails writes after {@code failAfter} tokens, as a gone client does. */
    static final class ClientSink implements StreamSink {
        final RequestContext ctx;
        final int failAfter;
        final List<String> events = new ArrayList<>();
        Throwable error;
        int tokens;

        ClientSink(RequestContext ctx, int failAfter) {
            this.ctx = ctx;
            this.failAfter = failAfter;
        }

        ClientSink(RequestContext ctx) {
            this(ctx, Integer.MAX_VALUE);
        }

        @Override
        public void onToken(String text) {
            if (tokens >= failAfter) throw new ClientDisconnectedException(new IOException("Broken pipe"));
            tokens++;
            ctx.markFirstTokenSent(System.nanoTime());
            events.add("token");
        }

        @Override
        public void onComplete(Completion completion) {
            events.add("complete");
        }

        @Override
        public void onError(Throwable e) {
            error = e;
            events.add("error:" + (e instanceof GatewayException g ? g.code().code() : e.getClass().getSimpleName()));
        }
    }

    /** Runs the request through the real pipeline (filter exceptions become onError, as in production). */
    ClientSink run(RequestContext ctx, Terminal terminal) {
        return run(ctx, terminal, new ClientSink(ctx));
    }

    ClientSink run(RequestContext ctx, Terminal terminal, ClientSink sink) {
        new DefaultGatewayPipeline(List.of(filter), terminal, List.of(), registry).run(ctx, sink);
        return sink;
    }

    double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    // ---- AC-t02-4: unused reservation comes back ----

    @Test
    void ac_t02_4_reserve1000Use300Leaves700BackForTheNextRequest() {
        ClientSink first = run(ctx(1_000), recording(succeeded(OPUS, 100, 200)));
        assertThat(first.events).containsExactly("token", "complete");
        assertThat(limiter.tokens(TENANT)).isEqualTo(700);

        // The next request's outcome shows exactly 700 came back: 701 is refused, 700 is admitted.
        ClientSink tooMuch = run(ctx(701), recording(succeeded(OPUS, 1, 1)));
        assertThat(tooMuch.events).containsExactly("error:rate_limited");
        var probe = new AtomicInteger();
        ClientSink fits = run(ctx(700), (c, s) -> {
            probe.incrementAndGet();
            recording(succeeded(OPUS, 600, 100)).call(c, s);
        });
        assertThat(fits.events).containsExactly("token", "complete");
        assertThat(probe).hasValue(1);
        assertThat(limiter.tokens(TENANT)).isZero();
    }

    @Test
    void successSettlesExactlyOnceAndAddsTheCostToTheBudget() {
        ClientSink sink = run(ctx(1_000), recording(succeeded(OPUS, 100, 200)));

        assertThat(sink.events).containsExactly("token", "complete");
        assertThat(limiter.acquireCalls()).isEqualTo(1);
        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThat(ledger.adds).hasValue(1);
        // 100 x $4 + 200 x $20 per Mtok = 400 + 4,000 micro-USD
        assertThat(ledger.spentMicroUsd(TENANT, NOV)).isEqualTo(4_400);
    }

    // ---- AC-t02-5: failed provider call ----

    @Test
    void ac_t02_5_failedProviderCallWithNoUsageGetsItsFullReservationRefunded() {
        ClientSink sink = run(ctx(1_000), recording(failed(OPUS, Usage.ZERO, false)));

        assertThat(sink.events).containsExactly("error:provider_error");
        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY);
        assertThat(ledger.adds).hasValue(0);
    }

    /**
     * AC-t02-5 through the real terminal (the t03 router): an HTTP 500 before any token bills nothing (D-34), on the
     * first attempt and on its retry, so all comes back.
     */
    @Test
    void ac_t02_5_httpErrorBeforeFirstTokenThroughTheRouterRefundsTheFullReservation() {
        var provider = new FakeLlmProvider("claude", Set.of(OPUS)).defaultScript(FakeLlmProvider.failBeforeFirstToken(500));
        RequestContext ctx = ctx(1_000);

        ClientSink sink = run(ctx, RouterFixture.single(provider));

        assertThat(sink.events).containsExactly("error:all_providers_failed");
        assertThat(provider.calls()).isEqualTo(2);
        assertThat(ctx.attempts()).hasSize(2);
        assertThat(ctx.billedUsage()).isEqualTo(Usage.ZERO);
        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY);
        assertThat(ledger.adds).hasValue(0);
    }

    @Test
    void terminalThatThrowsStillSettlesOnceAndTheExceptionPropagates() {
        RequestContext ctx = ctx(1_000);
        Terminal throwing = (c, s) -> {
            c.recordAttempt(failed(OPUS, new Usage(5, 0), true));
            throw new IllegalStateException("adapter bug");
        };

        assertThatThrownBy(() -> filter.handle(ctx, new ClientSink(ctx), new FilterChain(List.of(), throwing)))
                .isInstanceOf(IllegalStateException.class).hasMessage("adapter bug");

        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY - 5);
        assertThat(ledger.spentMicroUsd(TENANT, NOV)).isEqualTo(20); // 5 x $4 per Mtok
    }

    // ---- client disconnect (C23) ----

    @Test
    void clientDisconnectMidStreamSettlesOnceChargingTheTokensProducedSoFar() {
        var provider = new FakeLlmProvider("claude", Set.of(OPUS))
                .enqueue(FakeLlmProvider.reply("aaa", "bbb", "ccc", "ddd", "eee"));
        RequestContext ctx = ctx(OPUS, 1_000, true);

        run(ctx, RouterFixture.single(provider), new ClientSink(ctx, 2));

        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.CLIENT_CANCELLED);
        assertThat(provider.cancellations()).isEqualTo(1);
        Usage billed = ctx.billedUsage();
        assertThat(billed.inputTokens()).isEqualTo(INPUT_ESTIMATE);
        assertThat(billed.outputTokens()).isPositive();
        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY - billed.total());
        assertThat(ledger.adds).hasValue(1);
        assertThat(ledger.spentMicroUsd(TENANT, NOV)).isEqualTo(COSTS.costMicroUsd(ctx.attempts()));
    }

    @Test
    void clientDisconnectExceptionLeavesTheFilterAfterSettlement() {
        RequestContext ctx = ctx(OPUS, 1_000, true);
        Terminal disconnecting = (c, s) -> {
            c.recordAttempt(new Attempt("claude", OPUS, false, AttemptOutcome.CLIENT_CANCELLED, new Usage(5, 3), true,
                    1, 1, "CLIENT_CANCELLED"));
            throw new ClientDisconnectedException(new IOException("Broken pipe"));
        };

        assertThatThrownBy(() -> filter.handle(ctx, new ClientSink(ctx), new FilterChain(List.of(), disconnecting)))
                .isInstanceOf(ClientDisconnectedException.class);

        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY - 8);
        assertThat(ledger.adds).hasValue(1);
    }

    // ---- settlement details (D-33) ----

    @Test
    void settlesFromEveryAttemptEachPricedByItsOwnModel() {
        ClientSink sink = run(ctx(1_000), recording(failed(OPUS, new Usage(5, 0), true), succeeded(HAIKU, 100, 50)));

        assertThat(sink.events).containsExactly("token", "complete");
        assertThat(limiter.refundCalls()).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY - 155);
        // opus 5 x 4 = 20 ; haiku 100 x 0.10 + 50 x 0.50 = 35
        assertThat(ledger.spentMicroUsd(TENANT, NOV)).isEqualTo(55);
    }

    @Test
    void billedAboveTheReservationTakesNothingExtraFromTheBucket() {
        run(ctx(1_000), recording(succeeded(OPUS, 400, 800))); // input under-estimated

        assertThat(limiter.refundCalls()).isZero();
        assertThat(limiter.acquireCalls()).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isZero();
        assertThat(ledger.spentMicroUsd(TENANT, NOV)).isEqualTo(400 * 4 + 800 * 20);
    }

    @Test
    void freeModelAddsNothingToTheBudget() {
        run(ctx(LLAMA, 1_000, false), recording(succeeded(LLAMA, 100, 100)));

        assertThat(ledger.adds).hasValue(0);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY - 200);
    }

    @Test
    void budgetMonthIsTheUtcCalendarMonth() {
        // 00:30 UTC on 1 December is still 30 November in America/New_York: the budget month is December.
        var december = Clock.fixed(Instant.parse("2026-12-01T00:30:00Z"), ZoneOffset.UTC);
        ledger.set(TENANT, NOV, BUDGET_MICRO); // November is exhausted
        var decemberFilter = filter(ledger, december);
        RequestContext ctx = ctx(1_000);
        var sink = new ClientSink(ctx);

        new DefaultGatewayPipeline(List.of(decemberFilter), recording(succeeded(OPUS, 10, 10)), List.of(), registry)
                .run(ctx, sink);

        assertThat(sink.events).containsExactly("token", "complete");
        assertThat(ledger.spentMicroUsd(TENANT, YearMonth.of(2026, 12))).isEqualTo(10 * 4 + 10 * 20);
        assertThat(ledger.spentMicroUsd(TENANT, NOV)).isEqualTo(BUDGET_MICRO);
    }

    // ---- failures inside settlement never fail the request ----

    @Test
    void refundFailureIsCountedAndTheRequestStillSucceeds() {
        Terminal redisDiesDuringTheCall = (c, s) -> {
            limiter.failWith(new IllegalStateException("redis down"));
            recording(succeeded(OPUS, 100, 100)).call(c, s);
        };
        RequestContext ctx = ctx(1_000);

        ClientSink sink = run(ctx, redisDiesDuringTheCall);

        assertThat(sink.events).containsExactly("token", "complete");
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.SUCCESS);
        assertThat(limiter.refundCalls()).isEqualTo(1); // tried once, not retried
        assertThat(counter(MetricNames.LIMIT_FAIL_OPEN, "component", "limiter")).isEqualTo(1);
        assertThat(ledger.adds).hasValue(1); // the budget is still settled
    }

    @Test
    void budgetAddFailureIsCountedAndTheRequestStillSucceeds() {
        ledger.failAdds = true;
        RequestContext ctx = ctx(1_000);

        ClientSink sink = run(ctx, recording(succeeded(OPUS, 100, 100)));

        assertThat(sink.events).containsExactly("token", "complete");
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.SUCCESS);
        assertThat(ledger.adds).hasValue(1);
        assertThat(counter(MetricNames.LIMIT_FAIL_OPEN, "component", "budget")).isEqualTo(1);
        assertThat(limiter.tokens(TENANT)).isEqualTo(CAPACITY - 200); // refund unaffected
    }

    @Test
    void budgetReadFailureSkipsTheCheckAndIsCounted() {
        ledger.failReads = true;

        ClientSink sink = run(ctx(1_000), recording(succeeded(OPUS, 10, 10)));

        assertThat(sink.events).containsExactly("token", "complete");
        assertThat(counter(MetricNames.LIMIT_FAIL_OPEN, "component", "budget")).isEqualTo(1);
    }

    // ---- AC-t02-6: budget ----

    @Test
    void ac_t02_6_overBudgetIs429BudgetExceededWithoutRetryAfterAndNoProviderCall() {
        ledger.set(TENANT, NOV, BUDGET_MICRO);
        var provider = new FakeLlmProvider("claude", Set.of(OPUS));
        RequestContext ctx = ctx(1_000);

        ClientSink sink = run(ctx, RouterFixture.single(provider));

        assertThat(sink.events).containsExactly("error:budget_exceeded");
        var error = (GatewayException) sink.error;
        assertThat(error.code().httpStatus()).isEqualTo(429);
        assertThat(error.responseHeaders()).doesNotContainKey("Retry-After");
        assertThat(provider.calls()).isZero();
        assertThat(limiter.acquireCalls()).isZero();
        assertThat(limiter.refundCalls()).isZero();
        assertThat(ctx.attempts()).isEmpty();
        assertThat(counter(MetricNames.LIMIT_REJECTIONS, "tenant", TENANT, "reason", "budget_exceeded")).isEqualTo(1);
    }

    @Test
    void oneMicroUsdBelowTheBudgetIsStillAdmitted() {
        ledger.set(TENANT, NOV, BUDGET_MICRO - 1);

        ClientSink sink = run(ctx(1_000), recording(succeeded(OPUS, 10, 10)));

        assertThat(sink.events).containsExactly("token", "complete");
    }

    // ---- rate limited ----

    @Test
    void emptyBucketIs429RateLimitedWithRetryAfterAndNothingToRefund() {
        limiter.denyRetryAfterMs(1_500);
        assertThat(limiter.tryAcquire(TENANT, CAPACITY, BucketSpec.perMinute(CAPACITY)).allowed()).isTrue();
        var provider = new FakeLlmProvider("claude", Set.of(OPUS));

        ClientSink sink = run(ctx(10), RouterFixture.single(provider));

        assertThat(sink.events).containsExactly("error:rate_limited");
        assertThat(sink.error).isInstanceOf(RateLimitedException.class);
        assertThat(((RateLimitedException) sink.error).responseHeaders()).containsEntry("Retry-After", "2");
        assertThat(provider.calls()).isZero();
        assertThat(limiter.refundCalls()).isZero();
        assertThat(ledger.adds).hasValue(0);
        assertThat(counter(MetricNames.LIMIT_REJECTIONS, "tenant", TENANT, "reason", "rate_limited")).isEqualTo(1);
    }

    // ---- AC-t02-8: request larger than the bucket ----

    @Test
    void ac_t02_8_requestLargerThanCapacityIs400AndNeverTouchesTheBucket() {
        var provider = new FakeLlmProvider("claude", Set.of(OPUS));

        ClientSink sink = run(ctx(CAPACITY + 1), RouterFixture.single(provider));

        assertThat(sink.events).containsExactly("error:invalid_request");
        assertThat(sink.error.getMessage()).contains("1001").contains("1000");
        assertThat(limiter.acquireCalls()).isZero();
        assertThat(limiter.refundCalls()).isZero();
        assertThat(limiter.tokens(TENANT)).isNull();
        assertThat(provider.calls()).isZero();
        assertThat(counter(MetricNames.LIMIT_REJECTIONS, "tenant", TENANT, "reason", "too_large")).isEqualTo(1);
    }

    @Test
    void requestOfExactlyTheCapacityIsAdmitted() {
        ClientSink sink = run(ctx(CAPACITY), recording(succeeded(OPUS, 10, 10)));

        assertThat(sink.events).containsExactly("token", "complete");
    }

    // ---- bucket shape ----

    @Test
    void bucketCapacityIsTheTenantTpmAndRefillFollowsTheRefillPeriod() {
        assertThat(new RateLimitProperties(Duration.ofMinutes(1)).bucketFor(10_000)).isEqualTo(BucketSpec.perMinute(10_000));
        assertThat(new RateLimitProperties(Duration.ofSeconds(1)).bucketFor(600)).isEqualTo(new BucketSpec(600, 36_000));
        assertThat(new RateLimitProperties(null).refillPeriod()).isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(() -> new RateLimitProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    /** In-memory ledger that counts adds and can fail reads or adds. */
    static final class RecordingLedger implements BudgetLedger {
        final InMemoryBudgetLedger delegate = new InMemoryBudgetLedger();
        final AtomicInteger adds = new AtomicInteger();
        volatile boolean failReads;
        volatile boolean failAdds;

        @Override
        public long spentMicroUsd(String tenantId, YearMonth month) {
            if (failReads) throw new IllegalStateException("redis down");
            return delegate.spentMicroUsd(tenantId, month);
        }

        @Override
        public void addMicroUsd(String tenantId, YearMonth month, long microUsd) {
            adds.incrementAndGet();
            if (failAdds) throw new IllegalStateException("redis down");
            delegate.addMicroUsd(tenantId, month, microUsd);
        }

        void set(String tenantId, YearMonth month, long microUsd) {
            delegate.set(tenantId, month, microUsd);
        }
    }
}
