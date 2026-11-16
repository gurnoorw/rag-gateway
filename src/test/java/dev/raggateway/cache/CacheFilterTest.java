package dev.raggateway.cache;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.ChatRequest;
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
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.pipeline.FilterOrder;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.ratelimit.RateLimitFilter;
import dev.raggateway.ratelimit.RateLimitProperties;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import dev.raggateway.resilience.RouterFixture;
import dev.raggateway.support.FakeLlmProvider;
import dev.raggateway.support.FakeRateLimiter;
import dev.raggateway.support.InMemoryBudgetLedger;
import dev.raggateway.support.InMemoryResponseCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.raggateway.support.FakeLlmProvider.failAfterTokens;
import static dev.raggateway.support.FakeLlmProvider.failBeforeFirstToken;
import static dev.raggateway.support.FakeLlmProvider.reply;
import static dev.raggateway.support.FakeLlmProvider.script;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link CacheFilter} against the in-memory store (same lock and fail-open semantics as Redis), a trivial key
 * lambda, and the real terminal (the t03 fallback router over {@link FakeLlmProvider}s), inside the real pipeline
 * with the rate-limit filter behind it. The router records the serving attempt only after the adapter returns, which
 * is what the store rules depend on. HTTP, Redis and the canonical key are the t05 end-to-end test's job.
 */
class CacheFilterTest {

    static final String OPUS = "claude-opus-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final Tenant ACME = new Tenant("acme", "Acme", 1_000_000, BigDecimal.TEN, Set.of());
    static final Tenant GLOBEX = new Tenant("globex", "Globex", 1_000_000, BigDecimal.TEN, Set.of());

    /** Trivial key until Dev A's canonical key is on main: tenant, model and messages are enough here. */
    static final CacheKeyStrategy KEYS = (tenant, r) -> "test:" + tenant + ":" + r.model() + ":" + r.messages();

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final InMemoryResponseCache store = new InMemoryResponseCache();
    final CountingCache cache = new CountingCache(store);
    final FakeLlmProvider claude = new FakeLlmProvider("claude", Set.of(OPUS));
    final FakeLlmProvider ollama = new FakeLlmProvider("ollama", Set.of(LLAMA));
    final RouterFixture fixture = new RouterFixture(List.of(claude, ollama), List.of(new ChainEntry("ollama", LLAMA)));
    final FakeRateLimiter limiter = new FakeRateLimiter();

    CacheFilter filter(CacheProperties properties) {
        return new CacheFilter(cache, KEYS, properties, registry);
    }

    DefaultGatewayPipeline pipeline(CacheFilter filter) {
        return pipeline(filter, fixture.router);
    }

    DefaultGatewayPipeline pipeline(CacheFilter filter, Terminal terminal) {
        var costs = new CostCalculator(new PriceTable(Map.of(
                OPUS, new PriceTable.Price(new BigDecimal("4.00"), new BigDecimal("20.00")),
                LLAMA, new PriceTable.Price(BigDecimal.ZERO, BigDecimal.ZERO))));
        var rateLimit = new RateLimitFilter(limiter, new InMemoryBudgetLedger(), new CharRatioTokenEstimator(), costs,
                new RateLimitProperties(Duration.ofMinutes(1)), registry);
        // Given out of order on purpose: the pipeline sorts by @Order.
        List<GatewayFilter> filters = List.of(rateLimit, filter);
        return new DefaultGatewayPipeline(filters, terminal, List.of(), registry);
    }

    final CacheFilter defaultFilter = filter(CacheProperties.defaults());
    final DefaultGatewayPipeline pipeline = pipeline(defaultFilter);

    static ChatRequest request(Double temperature, String prompt) {
        return new ChatRequest(OPUS, List.of(new Message(Role.USER, prompt)), 64, temperature, null, List.of(),
                true, false, null);
    }

    static RequestContext ctx(Tenant tenant, ChatRequest request) {
        return new RequestContext(UUID.randomUUID().toString(), System.nanoTime(),
                new ApiKeyPrincipal(UUID.randomUUID().toString(), tenant, Set.of(), false), request);
    }

    record Result(RequestContext ctx, CapturingSink sink) {
        String text() { return sink.text(); }

        CacheStatus status() { return ctx.cacheStatus(); }
    }

    Result run(DefaultGatewayPipeline p, RequestContext ctx, CapturingSink sink) {
        p.run(ctx, sink);
        return new Result(ctx, sink);
    }

    Result run(Tenant tenant, ChatRequest request) {
        return run(pipeline, ctx(tenant, request), new CapturingSink());
    }

    String key(Tenant tenant, ChatRequest request) {
        return KEYS.keyFor(tenant.id(), request);
    }

    double lookups(String result) {
        var c = registry.find(MetricNames.CACHE_LOOKUPS).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    // ---- placement (C3, D-40) ----

    @Test
    void runsAfterModelSelectionAndBeforeTheRateLimiter() {
        int order = CacheFilter.class.getAnnotation(Order.class).value();
        assertThat(order).isEqualTo(FilterOrder.CACHE).isGreaterThan(FilterOrder.MODEL_SELECTION)
                .isLessThan(FilterOrder.RATE_LIMIT);
        assertThat(pipeline.filters().get(0)).isSameAs(defaultFilter);
        assertThat(pipeline.filters().get(1)).isInstanceOf(RateLimitFilter.class);
    }

    // ---- eligibility ----

    @Test
    void ac_t05_4_temperatureAboveZeroIsNotEligibleAndCallsTheProviderEachTime() {
        ChatRequest warm = request(0.7, "hello");
        Result first = run(ACME, warm);
        Result second = run(ACME, warm);

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(first.status()).isEqualTo(CacheStatus.NOT_ELIGIBLE);
        assertThat(second.status()).isEqualTo(CacheStatus.NOT_ELIGIBLE);
        assertThat(store.puts()).isZero();
        assertThat(cache.gets.get() + cache.locks.get()).as("no Redis call for an ineligible request").isZero();
        assertThat(lookups("not_eligible")).isEqualTo(2);
    }

    @Test
    void absentTemperatureIsNotEligible() {
        run(ACME, request(null, "hello"));
        Result second = run(ACME, request(null, "hello"));

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(second.status()).isEqualTo(CacheStatus.NOT_ELIGIBLE);
        assertThat(store.puts()).isZero();
    }

    @Test
    void disabledCacheIsNotEligibleEvenAtTemperatureZero() {
        var p = pipeline(filter(new CacheProperties(false, null, null, null)));
        run(p, ctx(ACME, request(0.0, "hello")), new CapturingSink());
        Result second = run(p, ctx(ACME, request(0.0, "hello")), new CapturingSink());

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(second.status()).isEqualTo(CacheStatus.NOT_ELIGIBLE);
        assertThat(cache.gets.get() + cache.locks.get()).isZero();
    }

    @Test
    void bypassHeaderNeitherReadsNorWrites() {
        ChatRequest cold = request(0.0, "hello");
        run(ACME, cold);                                        // stored
        RequestContext bypass = ctx(ACME, cold);
        bypass.setCacheBypassRequested(true);
        int getsBefore = cache.gets.get();

        Result r = run(pipeline, bypass, new CapturingSink());

        assertThat(r.status()).isEqualTo(CacheStatus.BYPASS);
        assertThat(claude.calls()).as("the stored answer is not used").isEqualTo(2);
        assertThat(cache.gets.get()).isEqualTo(getsBefore);
        assertThat(store.puts()).as("nor overwritten").isEqualTo(1);
        assertThat(lookups("bypass")).isEqualTo(1);
    }

    @Test
    void bypassHeaderOnAnIneligibleRequestReportsNotEligible() {
        RequestContext c = ctx(ACME, request(0.7, "hello"));
        c.setCacheBypassRequested(true);

        assertThat(run(pipeline, c, new CapturingSink()).status()).isEqualTo(CacheStatus.NOT_ELIGIBLE);
    }

    // ---- hit ----

    @Test
    void ac_t05_1_secondIdenticalRequestIsAHitWithIdenticalContentAndNoProviderCall() {
        claude.defaultScript(reply("Hel", "lo"));
        ChatRequest cold = request(0.0, "hello");

        Result first = run(ACME, cold);
        Result second = run(ACME, cold);

        assertThat(claude.calls()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(CacheStatus.MISS);
        assertThat(second.status()).isEqualTo(CacheStatus.HIT);
        assertThat(second.text()).isEqualTo(first.text()).isEqualTo("Hello");
        assertThat(second.sink.tokens).as("the content is sent once").containsExactly("Hello");
        assertThat(second.sink.completion.usage()).isEqualTo(Usage.ZERO);
        assertThat(second.sink.completion.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(second.ctx.attempts()).as("no attempt, so no cost and provider 'cache' in the log").isEmpty();
        assertThat(second.ctx.billedUsage()).isEqualTo(Usage.ZERO);
        assertThat(second.ctx.outcome()).isEqualTo(RequestOutcome.SUCCESS);
        assertThat(lookups("miss")).isEqualTo(1);
        assertThat(lookups("hit")).isEqualTo(1);
    }

    @Test
    void ac_t02_8_and_ac_t05_10_aHitNeverTouchesTheTokenBucket() {
        ChatRequest cold = request(0.0, "hello");
        run(ACME, cold);
        int acquires = limiter.acquireCalls();
        int refunds = limiter.refundCalls();
        Long tokens = limiter.tokens(ACME.id());

        Result hit = run(ACME, cold);

        assertThat(hit.status()).isEqualTo(CacheStatus.HIT);
        assertThat(limiter.acquireCalls()).isEqualTo(acquires);
        assertThat(limiter.refundCalls()).isEqualTo(refunds);
        assertThat(limiter.tokens(ACME.id())).isEqualTo(tokens);
    }

    @Test
    void ac_t05_2_anotherTenantDoesNotShareTheEntry() {
        ChatRequest cold = request(0.0, "hello");
        run(ACME, cold);
        Result other = run(GLOBEX, cold);

        assertThat(other.status()).isEqualTo(CacheStatus.MISS);
        assertThat(claude.calls()).isEqualTo(2);
    }

    @Test
    void storedValueHoldsTheAnswerItsFinishReasonAndWhoProducedIt() {
        claude.defaultScript(reply("a", "b"));
        ChatRequest cold = request(0.0, "hello");
        run(ACME, cold);

        CachedResponse stored = store.get(key(ACME, cold)).orElseThrow();
        assertThat(stored.content()).isEqualTo("ab");
        assertThat(stored.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(stored.model()).isEqualTo("fake-model");
        assertThat(stored.provider()).isEqualTo("claude");
        assertThat(stored.originalUsage()).isEqualTo(new Usage(10, 2));
        assertThat(store.ttl(key(ACME, cold))).isBetween(Duration.ofHours(6).minusMinutes(1), Duration.ofHours(6));
    }

    @Test
    void anAnswerCutOffByMaxTokensIsStoredAndAnEmptyOneReplaysAsCompletionOnly() {
        claude.defaultScript(script(new FakeLlmProvider.Complete(
                new Completion(FinishReason.LENGTH, new Usage(10, 64), "fake-model"))));
        ChatRequest cold = request(0.0, "hello");
        run(ACME, cold);

        Result hit = run(ACME, cold);

        assertThat(hit.status()).isEqualTo(CacheStatus.HIT);
        assertThat(hit.sink.tokens).isEmpty();
        assertThat(hit.sink.completion.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(claude.calls()).isEqualTo(1);
    }

    @Test
    void theStoreHappensAfterTheClientStreamIsClosed() {
        ChatRequest cold = request(0.0, "hello");
        CapturingSink sink = new CapturingSink();
        List<Boolean> clientCompletedAtPut = new ArrayList<>();
        cache.onPut = () -> clientCompletedAtPut.add(sink.completion != null);

        run(pipeline, ctx(ACME, cold), sink);

        assertThat(clientCompletedAtPut).containsExactly(true);
    }

    // ---- never stored ----

    @Test
    void ac_t05_5_aFailedProviderCallStoresNothing() {
        claude.defaultScript(failBeforeFirstToken(500));
        ollama.defaultScript(failBeforeFirstToken(500));
        ChatRequest cold = request(0.0, "hello");

        Result r = run(ACME, cold);

        assertThat(r.ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(r.status()).isEqualTo(CacheStatus.MISS);
        assertThat(store.puts()).isZero();
        assertThat(store.tryLock(key(ACME, cold), Duration.ofSeconds(1))).as("lock released").isPresent();
    }

    @Test
    void ac_t05_6_anAnswerServedByTheFallbackIsNotStored() {
        claude.defaultScript(failBeforeFirstToken(500));
        ollama.defaultScript(reply("from ", "llama"));
        ChatRequest cold = request(0.0, "hello");

        Result r = run(ACME, cold);

        assertThat(r.text()).isEqualTo("from llama");
        assertThat(r.ctx.fallbackUsed()).isTrue();
        assertThat(store.puts()).isZero();
        assertThat(run(ACME, cold).status()).as("the next request is a miss again").isEqualTo(CacheStatus.MISS);
    }

    @Test
    void aRetryOnTheSelectedProviderThatSucceedsIsStored() {
        claude.enqueue(failBeforeFirstToken(503)).defaultScript(reply("ok"));
        ChatRequest cold = request(0.0, "hello");

        Result r = run(ACME, cold);

        assertThat(r.ctx.attempts()).extracting(a -> a.outcome())
                .containsExactly(AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, AttemptOutcome.SUCCEEDED);
        assertThat(store.get(key(ACME, cold)).orElseThrow().content()).isEqualTo("ok");
    }

    @Test
    void aRefusalIsNotStored() {
        claude.defaultScript(script(new FakeLlmProvider.Token("I can't help with that"),
                new FakeLlmProvider.Complete(new Completion(FinishReason.CONTENT_FILTER, new Usage(10, 5), "fake-model"))));

        run(ACME, request(0.0, "hello"));

        assertThat(store.puts()).isZero();
    }

    @Test
    void aStreamThatBreaksMidwayIsNotStored() {
        claude.defaultScript(failAfterTokens("partial "));
        ChatRequest cold = request(0.0, "hello");

        Result r = run(ACME, cold);

        assertThat(r.text()).isEqualTo("partial ");
        assertThat(r.ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(store.puts()).isZero();
    }

    @Test
    void aClientThatDisconnectsMidStreamStoresNothing() {
        claude.defaultScript(reply("a", "b", "c"));
        ChatRequest cold = request(0.0, "hello");

        Result r = run(pipeline, ctx(ACME, cold), new CapturingSink().disconnectAfterTokens(1));

        assertThat(r.ctx.outcome()).isEqualTo(RequestOutcome.CLIENT_CANCELLED);
        assertThat(store.puts()).isZero();
        assertThat(store.tryLock(key(ACME, cold), Duration.ofSeconds(1))).as("lock released").isPresent();
    }

    @Test
    void aClientThatDisconnectsOnTheFinalEventStoresNothing() {
        ChatRequest cold = request(0.0, "hello");

        Result r = run(pipeline, ctx(ACME, cold), new CapturingSink().disconnectOnComplete());

        assertThat(r.ctx.outcome()).isEqualTo(RequestOutcome.CLIENT_CANCELLED);
        assertThat(store.puts()).isZero();
    }

    @Test
    void anAttemptOnAnotherModelThanTheSelectedOneIsNotStored() {
        // A terminal that answers with a model the selection did not choose, without flagging it as a fallback.
        Terminal wrongModel = (c, s) -> {
            c.startAttempt("claude", "claude-haiku-5-5", false);
            s.onToken("x");
            s.onComplete(new Completion(FinishReason.STOP, new Usage(1, 1), "claude-haiku-5-5"));
            c.recordAttempt(new Attempt("claude", "claude-haiku-5-5", false,
                    AttemptOutcome.SUCCEEDED, new Usage(1, 1), false, 1, 1, null));
        };

        run(pipeline(defaultFilter, wrongModel), ctx(ACME, request(0.0, "hello")), new CapturingSink());

        assertThat(store.puts()).isZero();
    }

    @Test
    void theLockIsReleasedWhenTheChainThrows() {
        Terminal broken = (c, s) -> {
            throw new IllegalStateException("terminal bug");
        };
        ChatRequest cold = request(0.0, "hello");

        Result r = run(pipeline(defaultFilter, broken), ctx(ACME, cold), new CapturingSink());

        assertThat(r.ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(store.tryLock(key(ACME, cold), Duration.ofSeconds(1))).isPresent();
    }

    // ---- single-flight ----

    @Test
    void ac_t05_7_fiftyConcurrentMissesMakeOneCall() throws Exception {
        int n = 50;
        var providerGate = new CountDownLatch(1);
        claude.defaultScript(script(new FakeLlmProvider.Await(providerGate),
                new FakeLlmProvider.Token("same "), new FakeLlmProvider.Token("answer"),
                new FakeLlmProvider.Complete(new Completion(FinishReason.STOP, new Usage(10, 2), "fake-model"))));
        ChatRequest cold = request(0.0, "hello");
        var start = new CyclicBarrier(n);

        List<Result> results;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return run(ACME, cold);
                }));
            }
            // Hold the provider until every other request has found the lock taken: all 50 are in flight at once.
            await().atMost(Duration.ofSeconds(10)).until(() -> cache.refusedThreads.size() >= n - 1);
            providerGate.countDown();
            results = new ArrayList<>();
            for (Future<Result> f : futures) results.add(f.get(20, TimeUnit.SECONDS));
        }

        assertThat(claude.calls()).isEqualTo(1);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.ctx.outcome()).isEqualTo(RequestOutcome.SUCCESS);
            assertThat(r.text()).isEqualTo("same answer");
        });
        assertThat(results).filteredOn(r -> r.status() == CacheStatus.MISS).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == CacheStatus.HIT).hasSize(n - 1);
        assertThat(results).filteredOn(r -> r.status() == CacheStatus.HIT)
                .allSatisfy(r -> assertThat(r.ctx.waitNanos()).as("waiting is a deliberate wait").isPositive());
        assertThat(lookups("hit") + lookups("miss")).isEqualTo(n);
    }

    @Test
    void aHolderThatFailsLetsAWaiterTakeOverWithoutWaitingTheLockTtl() throws Exception {
        var holderGate = new CountDownLatch(1);
        claude.enqueue(script(new FakeLlmProvider.Await(holderGate), new FakeLlmProvider.Fail(
                        new ProviderException(ProviderException.Kind.HTTP_STATUS,
                                "fake upstream 400", false, 400, null, null))))
                .defaultScript(reply("second ", "try"));
        ChatRequest cold = request(0.0, "hello");

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Result> holder = pool.submit(() -> run(ACME, cold));
            await().atMost(Duration.ofSeconds(5)).until(() -> claude.calls() == 1);
            Future<Result> waiter = pool.submit(() -> run(ACME, cold));
            await().atMost(Duration.ofSeconds(5)).until(() -> cache.refusedThreads.size() >= 1);
            holderGate.countDown();

            Result failed = holder.get(10, TimeUnit.SECONDS);
            Result tookOver = waiter.get(10, TimeUnit.SECONDS);

            assertThat(failed.ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
            assertThat(tookOver.ctx.outcome()).isEqualTo(RequestOutcome.SUCCESS);
            assertThat(tookOver.text()).isEqualTo("second try");
            assertThat(tookOver.status()).isEqualTo(CacheStatus.MISS);
            assertThat(tookOver.ctx.waitNanos()).as("well under the 10 s lock TTL")
                    .isPositive().isLessThan(Duration.ofSeconds(5).toNanos());
        }
        assertThat(claude.calls()).isEqualTo(2);
        assertThat(store.get(key(ACME, cold)).orElseThrow().content()).isEqualTo("second try");
    }

    @Test
    void afterAFillThatCannotBeStoredTheWaitersCallInParallelNotOneAfterAnother() throws Exception {
        // Outage: Claude rejects the gateway's key (moves on at once, D-37), the slow fallback answers; a fallback
        // answer is never stored (C21), so every waiter has to call. They must not queue behind each other (D-41).
        int waiters = 4;
        Duration fallbackTime = Duration.ofMillis(300);
        var holderGate = new CountDownLatch(1);
        claude.defaultScript(FakeLlmProvider.failBeforeFirstToken(401));
        ollama.enqueue(script(new FakeLlmProvider.Await(holderGate), new FakeLlmProvider.Token("fallback"),
                        new FakeLlmProvider.Complete(new Completion(FinishReason.STOP, new Usage(5, 1), LLAMA))))
                .defaultScript(FakeLlmProvider.slowReply(fallbackTime, "fallback"));
        ChatRequest cold = request(0.0, "hello");

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Result> holder = pool.submit(() -> run(ACME, cold));
            await().atMost(Duration.ofSeconds(5)).until(() -> ollama.calls() == 1);
            List<Future<Result>> rest = new ArrayList<>();
            for (int i = 0; i < waiters; i++) rest.add(pool.submit(() -> run(ACME, cold)));
            await().atMost(Duration.ofSeconds(5)).until(() -> cache.refusedThreads.size() >= waiters);
            long released = System.nanoTime();
            holderGate.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS).ctx.fallbackUsed()).isTrue();
            for (Future<Result> f : rest) assertThat(f.get(10, TimeUnit.SECONDS).text()).isEqualTo("fallback");
            Duration all = Duration.ofNanos(System.nanoTime() - released);

            // In parallel: about one fallback time. One after another would be 4 × 300 ms.
            assertThat(all).isLessThan(fallbackTime.multipliedBy(waiters).minusMillis(300));
        }
        assertThat(ollama.calls()).isEqualTo(1 + waiters);
        assertThat(store.get(key(ACME, cold))).as("fallback answers are never stored").isEmpty();
    }

    @Test
    void aHolderSlowerThanTheLockTtlLetsAWaiterCallOnceTheLockExpires() throws Exception {
        Duration lockTtl = Duration.ofMillis(300);
        var p = pipeline(filter(new CacheProperties(true, null, lockTtl, Duration.ofMillis(20))));
        var holderGate = new CountDownLatch(1);
        claude.enqueue(script(new FakeLlmProvider.Await(holderGate), new FakeLlmProvider.Token("slow"),
                        new FakeLlmProvider.Complete(new Completion(FinishReason.STOP, new Usage(10, 1), "fake-model"))))
                .defaultScript(reply("own call"));
        ChatRequest cold = request(0.0, "hello");

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Result> holder = pool.submit(() -> run(p, ctx(ACME, cold), new CapturingSink()));
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> claude.calls() == 1);
                // The holder streams longer than the lock lives: its lock expires and the waiter takes it over.
                Result waiter = run(p, ctx(ACME, cold), new CapturingSink());

                assertThat(waiter.text()).isEqualTo("own call");
                assertThat(waiter.status()).isEqualTo(CacheStatus.MISS);
                assertThat(waiter.ctx.waitNanos()).isPositive().isLessThanOrEqualTo(lockTtl.toNanos());
            } finally {
                holderGate.countDown();
            }
            assertThat(holder.get(10, TimeUnit.SECONDS).text()).isEqualTo("slow");
        }
        assertThat(claude.calls()).isEqualTo(2);
    }

    @Test
    void aWaiterThatNeverSeesTheLockFreeCallsThroughAfterTheLockTtl() {
        Duration lockTtl = Duration.ofMillis(300);
        var p = pipeline(filter(new CacheProperties(true, null, lockTtl, Duration.ofMillis(20))));
        cache.refuseLocks = true;    // e.g. another instance keeps re-taking the lock
        claude.defaultScript(reply("own call"));
        ChatRequest cold = request(0.0, "hello");

        Result waiter = run(p, ctx(ACME, cold), new CapturingSink());

        assertThat(waiter.text()).isEqualTo("own call");
        assertThat(waiter.status()).isEqualTo(CacheStatus.MISS);
        assertThat(waiter.ctx.waitNanos()).isGreaterThanOrEqualTo(lockTtl.toNanos())
                .isLessThan(lockTtl.plusSeconds(2).toNanos());
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(store.get(key(ACME, cold))).as("a qualifying answer is still stored").isPresent();
    }

    // ---- Redis down (D-17, D-40) ----

    @Test
    void whenRedisFailsEveryRequestGoesAheadAtOnceAndNothingIsStored() throws Exception {
        store.failing(true);
        int n = 5;
        var release = new CountDownLatch(1);
        claude.defaultScript(script(new FakeLlmProvider.Await(release), new FakeLlmProvider.Token("ok"),
                new FakeLlmProvider.Complete(new Completion(FinishReason.STOP, new Usage(10, 1), "fake-model"))));
        ChatRequest cold = request(0.0, "hello");

        List<Result> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    return run(ACME, cold);
                }));
            }
            // Nobody waits on a lock that a dead Redis can never hand over: all n reach the provider together.
            await().atMost(Duration.ofSeconds(5)).until(() -> claude.calls() == n);
            release.countDown();
            for (Future<Result> f : futures) results.add(f.get(10, TimeUnit.SECONDS));
        }

        assertThat(results).allSatisfy(r -> {
            assertThat(r.text()).isEqualTo("ok");
            assertThat(r.status()).isEqualTo(CacheStatus.MISS);
            assertThat(r.ctx.waitNanos()).isZero();
        });
        assertThat(store.failing(false).size()).isZero();
    }

    @Test
    void aFailedOpenLockStillStoresTheAnswerForTheWaitersBehindAnOrphanedLock() {
        // tryLock timed out on the client but may have set the lock on Redis with a token nobody holds:
        // waiters behind that lock can only be served by the stored answer.
        String orphan = store.tryLock(key(ACME, request(0.0, "hello")), Duration.ofSeconds(10)).orElseThrow();
        cache.lockFailsOpen = true;
        claude.defaultScript(reply("filled"));
        ChatRequest cold = request(0.0, "hello");

        Result holder = run(ACME, cold);
        cache.lockFailsOpen = false;
        Result waiter = run(ACME, cold);

        assertThat(holder.status()).isEqualTo(CacheStatus.MISS);
        assertThat(store.get(key(ACME, cold)).orElseThrow().content()).isEqualTo("filled");
        assertThat(waiter.status()).isEqualTo(CacheStatus.HIT);
        assertThat(waiter.text()).isEqualTo("filled");
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(orphan).isNotBlank();
    }

    // ---- fakes ----

    /** Client sink: records what the client would see; can simulate a client that goes away. */
    static final class CapturingSink implements StreamSink {
        final List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        volatile Completion completion;
        volatile Throwable error;
        private int disconnectAfterTokens = -1;
        private boolean disconnectOnComplete;

        CapturingSink disconnectAfterTokens(int n) {
            this.disconnectAfterTokens = n;
            return this;
        }

        CapturingSink disconnectOnComplete() {
            this.disconnectOnComplete = true;
            return this;
        }

        @Override
        public void onToken(String text) {
            if (disconnectAfterTokens >= 0 && tokens.size() >= disconnectAfterTokens) {
                throw new ClientDisconnectedException(new IOException("broken pipe"));
            }
            tokens.add(text);
        }

        @Override
        public void onComplete(Completion c) {
            if (disconnectOnComplete) throw new ClientDisconnectedException(new IOException("broken pipe"));
            completion = c;
        }

        @Override
        public void onError(Throwable e) {
            error = e;
        }

        String text() {
            return String.join("", tokens);
        }
    }

    /** Counts calls into the store and lets a test observe a put. */
    static final class CountingCache implements ResponseCache {
        final ResponseCache delegate;
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger locks = new AtomicInteger();
        final Set<Long> refusedThreads = ConcurrentHashMap.newKeySet();
        volatile Runnable onPut = () -> { };
        volatile boolean refuseLocks;
        volatile boolean lockFailsOpen;

        CountingCache(ResponseCache delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<CachedResponse> get(String key) {
            gets.incrementAndGet();
            return delegate.get(key);
        }

        @Override
        public void put(String key, CachedResponse value, Duration ttl) {
            onPut.run();
            delegate.put(key, value, ttl);
        }

        @Override
        public Optional<String> tryLock(String key, Duration ttl) {
            locks.incrementAndGet();
            if (lockFailsOpen) return Optional.of(FAILED_OPEN);
            Optional<String> token = refuseLocks ? Optional.empty() : delegate.tryLock(key, ttl);
            if (token.isEmpty()) refusedThreads.add(Thread.currentThread().threadId());
            return token;
        }

        @Override
        public void unlock(String key, String token) {
            delegate.unlock(key, token);
        }
    }
}
