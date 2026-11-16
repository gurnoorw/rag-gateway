package dev.raggateway.resilience;

import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.provider.ProviderException.Kind;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import dev.raggateway.support.FakeLlmProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class FallbackRouterTest {

    private static final String OPUS = "claude-opus-5-5";
    private static final String LLAMA = "llama3.2:1b";
    private static final List<ChainEntry> CHAIN = List.of(new ChainEntry("ollama", LLAMA));

    private final FakeLlmProvider claude = new FakeLlmProvider("claude", Set.of(OPUS))
            .defaultScript(FakeLlmProvider.failBeforeFirstToken(500));
    private final FakeLlmProvider ollama = new FakeLlmProvider("ollama", Set.of(LLAMA))
            .defaultScript(FakeLlmProvider.reply("from ", "llama"));
    private final RouterFixture fx = new RouterFixture(List.of(claude, ollama), CHAIN);

    // ---- helpers ----

    static RequestContext ctx(String model) {
        var tenant = new Tenant("t1", "Tenant 1", 10_000, BigDecimal.TEN, Set.of());
        var caller = new ApiKeyPrincipal(UUID.randomUUID().toString(), tenant, Set.of(), false);
        var request = new ChatRequest(model, List.of(new Message(Role.USER, "hello there, how are you")), 64,
                null, null, List.of(), true, false, null);
        return new RequestContext(UUID.randomUUID().toString(), System.nanoTime(), caller, request);
    }

    /** Records events like the client sink: tokens, then exactly one completion or error. */
    static class Sink implements StreamSink {
        final List<Object> events = Collections.synchronizedList(new ArrayList<>());
        final StringBuilder text = new StringBuilder();

        @Override
        public void onToken(String t) {
            text.append(t);
            events.add(t);
        }

        @Override
        public void onComplete(Completion c) {
            events.add(c);
        }

        @Override
        public void onError(Throwable e) {
            events.add(e);
        }

        GatewayException error() {
            Object last = events.get(events.size() - 1);
            assertThat(last).isInstanceOf(GatewayException.class);
            return (GatewayException) last;
        }

        boolean completed() {
            return !events.isEmpty() && events.get(events.size() - 1) instanceof Completion;
        }
    }

    private Sink run(RequestContext ctx) {
        var sink = new Sink();
        fx.router.call(ctx, sink);
        return sink;
    }

    private static ProviderException httpError(int status, boolean retryable, Duration retryAfter) {
        return new ProviderException(Kind.HTTP_STATUS, "fake upstream " + status, retryable, status, retryAfter, null);
    }

    private static FakeLlmProvider.Script fail(ProviderException e) {
        return FakeLlmProvider.script(new FakeLlmProvider.Fail(e));
    }

    // ---- AC-t03-1 ----

    @Test
    void ac_t03_1_primaryReturns500TwiceThenTheFallbackAnswersAndThePrimaryGotExactlyTwoCalls() {
        RequestContext ctx = ctx(OPUS);

        Sink sink = run(ctx);

        assertThat(sink.completed()).isTrue();
        assertThat(sink.text).hasToString("from llama");
        assertThat(claude.calls()).isEqualTo(2);
        assertThat(ollama.calls()).isEqualTo(1);
        assertThat(ctx.attempts()).extracting(Attempt::provider, Attempt::model, Attempt::fallback, Attempt::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("claude", OPUS, false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN),
                        org.assertj.core.groups.Tuple.tuple("claude", OPUS, false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN),
                        org.assertj.core.groups.Tuple.tuple("ollama", LLAMA, true, AttemptOutcome.SUCCEEDED));
        // An HTTP error before the first token bills nothing (D-34): only the fallback's reported usage is billed.
        assertThat(ctx.attempts().get(0).usage()).isEqualTo(Usage.ZERO);
        assertThat(ctx.attempts().get(0).usageEstimated()).isFalse();
        assertThat(ctx.billedUsage()).isEqualTo(new Usage(10, 2));
        assertThat(fx.counter(MetricNames.PROVIDER_RETRIES, "provider", "claude")).isEqualTo(1);
        assertThat(fx.counter(MetricNames.PROVIDER_FALLBACKS, "from_provider", "claude", "to_provider", "ollama"))
                .isEqualTo(1);
    }

    @Test
    void retryableFailureThenSuccessOnTheSameProviderNeedsNoFallback() {
        claude.enqueue(FakeLlmProvider.failBeforeFirstToken(429)).enqueue(FakeLlmProvider.reply("ok"));
        RequestContext ctx = ctx(OPUS);

        Sink sink = run(ctx);

        assertThat(sink.completed()).isTrue();
        assertThat(claude.calls()).isEqualTo(2);
        assertThat(ollama.calls()).isZero();
        assertThat(ctx.fallbackUsed()).isFalse();
    }

    @Test
    void connectionFailureBeforeTheFirstTokenIsRetriedAndChargesTheEstimatedInput() {
        claude.enqueue(fail(new ProviderException(Kind.CONNECTION, "reset", true, 0, null, null)))
                .enqueue(FakeLlmProvider.reply("ok"));
        RequestContext ctx = ctx(OPUS);

        run(ctx);

        // The provider may already have processed the prompt (D-34): input estimate, flagged as estimated.
        assertThat(ctx.attempts().get(0)).satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN);
            assertThat(a.errorKind()).isEqualTo("CONNECTION");
            assertThat(a.usageEstimated()).isTrue();
            assertThat(a.usage().inputTokens()).isPositive();
            assertThat(a.usage().outputTokens()).isZero();
        });
        assertThat(ctx.attempts().get(1).outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
    }

    // ---- D-28: every attempt is announced before the adapter is called ----

    @Test
    void everyAttemptIsAnnouncedBeforeTheAdapterCallIncludingRetriesAndTheFallback() {
        RequestContext ctx = ctx(OPUS);
        List<String> announced = new ArrayList<>();
        LlmProvider spyClaude = announcing(claude, ctx, announced);
        LlmProvider spyOllama = announcing(ollama, ctx, announced);
        var router = new RouterFixture(List.of(spyClaude, spyOllama), CHAIN).router;

        router.call(ctx, new Sink());

        assertThat(announced).containsExactly("claude/" + OPUS + "/false", "claude/" + OPUS + "/false",
                "ollama/" + LLAMA + "/true");
    }

    private static LlmProvider announcing(FakeLlmProvider delegate, RequestContext ctx, List<String> seen) {
        return new LlmProvider() {
            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public boolean supports(String model) {
                return delegate.supports(model);
            }

            @Override
            public void stream(ChatRequest request, StreamSink sink) throws ProviderException {
                seen.add(ctx.currentProvider() + "/" + ctx.currentModel() + "/" + ctx.currentAttemptIsFallback());
                assertThat(request.model()).isEqualTo(ctx.currentModel());
                delegate.stream(request, sink);
            }
        };
    }

    // ---- AC-t03-3 ----

    @Test
    void ac_t03_3_failureAfterTheFirstTokenIsNotRetriedNorFallenBackAndNoTextRepeats() {
        claude.enqueue(FakeLlmProvider.failAfterTokens("Hel", "lo"));
        RequestContext ctx = ctx(OPUS);

        Sink sink = run(ctx);

        assertThat(sink.text).hasToString("Hello");
        assertThat(sink.events).hasSize(3);
        assertThat(sink.error().code()).isEqualTo(ErrorCode.PROVIDER_ERROR);
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(ollama.calls()).isZero();
        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.FAILED_MID_STREAM);
            assertThat(a.usageEstimated()).isTrue();
            assertThat(a.usage().outputTokens()).isPositive();
            assertThat(a.errorKind()).isEqualTo("CONNECTION");
        });
        // A broken connection mid-stream is still a health signal for the breaker.
        assertThat(fx.breaker("claude").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    // ---- AC-t03-6 and the move-on rule ----

    @Test
    void ac_t03_6_upstream400IsA400ToTheClientAndTheFallbackGetsNoCall() {
        claude.enqueue(FakeLlmProvider.failBeforeFirstToken(400));
        RequestContext ctx = ctx(OPUS);

        Sink sink = run(ctx);

        assertThat(sink.events).singleElement();
        assertThat(sink.error().code()).isEqualTo(ErrorCode.UPSTREAM_REJECTED);
        assertThat(sink.error().code().httpStatus()).isEqualTo(400);
        assertThat(sink.error().getMessage()).isEqualTo("provider claude rejected the request (HTTP 400)");
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(ollama.calls()).isZero();
        // The caller's bad request says nothing about the provider's health.
        assertThat(fx.breaker("claude").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void upstream401Or403MovesOnAtOnceWithoutRetryAndTheBreakerIgnoresIt() {
        for (int status : new int[]{401, 403}) {
            claude.reset();
            ollama.reset();
            claude.defaultScript(FakeLlmProvider.failBeforeFirstToken(status));
            // More than a whole breaker window: an ignored error must never open it.
            for (int i = 0; i < 25; i++) {
                RequestContext ctx = ctx(OPUS);
                Sink sink = run(ctx);
                assertThat(sink.completed()).isTrue();
                assertThat(ctx.fallbackUsed()).isTrue();
            }
            assertThat(claude.calls()).as("HTTP %d: one call per request, no retry", status).isEqualTo(25);
            assertThat(ollama.calls()).isEqualTo(25);
            assertThat(fx.breaker("claude").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(fx.breaker("claude").getMetrics().getNumberOfBufferedCalls()).isZero();
        }
        assertThat(fx.counter(MetricNames.PROVIDER_RETRIES, "provider", "claude")).isZero();
    }

    @Test
    void ollama404ModelNotPulledGetsNoRetryAndNoFallback() {
        // The caller selected the local model; the next step would be Claude.
        var fixture = new RouterFixture(List.of(claude, ollama), List.of(new ChainEntry("claude", OPUS)));
        claude.defaultScript(FakeLlmProvider.reply("claude"));
        ollama.enqueue(fail(httpError(404, false, null)));
        RequestContext ctx = ctx(LLAMA);
        var sink = new Sink();

        fixture.router.call(ctx, sink);

        assertThat(sink.error().code()).isNotEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
        assertThat(ollama.calls()).isEqualTo(1);
        assertThat(claude.calls()).isZero();
    }

    // ---- AC-t03-7 ----

    @Test
    void ac_t03_7_bothProvidersFailingIsA503AllProvidersFailed() {
        ollama.defaultScript(FakeLlmProvider.failBeforeFirstToken(503));
        RequestContext ctx = ctx(OPUS);

        Sink sink = run(ctx);

        assertThat(sink.events).singleElement();
        assertThat(sink.error().code()).isEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
        assertThat(sink.error().code().httpStatus()).isEqualTo(503);
        assertThat(sink.error().getMessage()).isEqualTo("all providers failed (claude: HTTP 500; ollama: HTTP 503)");
        assertThat(claude.calls()).isEqualTo(2);
        assertThat(ollama.calls()).isEqualTo(2);
        assertThat(ctx.attempts()).hasSize(4).allSatisfy(a -> assertThat(a.usage()).isEqualTo(Usage.ZERO));
        assertThat(ctx.billedUsage()).isEqualTo(Usage.ZERO);
    }

    // ---- AC-t03-8 ----

    @Test
    void ac_t03_8_whenTheFallbackAnswersTheResponseNamesTheFallbackModelAndProvider() {
        RequestContext ctx = ctx(OPUS);
        AtomicReference<String> atFirstToken = new AtomicReference<>();
        var sink = new Sink() {
            @Override
            public void onToken(String t) {
                // What the client sink reads when it commits headers and the body's model at the first byte.
                if (atFirstToken.get() == null) {
                    atFirstToken.set(ctx.currentProvider() + "/" + ctx.currentModel() + "/" + ctx.currentAttemptIsFallback());
                }
                super.onToken(t);
            }
        };

        fx.router.call(ctx, sink);

        assertThat(atFirstToken.get()).isEqualTo("ollama/" + LLAMA + "/true");
        assertThat(ollama.requests()).singleElement().satisfies(r -> assertThat(r.model()).isEqualTo(LLAMA));
        assertThat(ctx.servingAttempt()).hasValueSatisfying(a -> {
            assertThat(a.provider()).isEqualTo("ollama");
            assertThat(a.model()).isEqualTo(LLAMA);
        });
        assertThat(ctx.fallbackUsed()).as("C21: never cached").isTrue();
    }

    // ---- AC-t03-4 / AC-t03-5: breaker with a controlled clock ----

    /** Ten requests of two failed attempts each fill the window of 20 with 100 % failures. */
    private void openThePrimaryBreaker() {
        for (int i = 0; i < 10; i++) run(ctx(OPUS));
        assertThat(claude.calls()).isEqualTo(20);
        assertThat(fx.breaker("claude").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void breakerStaysClosedBelowTheMinimumNumberOfCalls() {
        for (int i = 0; i < 9; i++) run(ctx(OPUS)); // 18 failed calls < window of 20
        assertThat(fx.breaker("claude").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void ac_t03_4_afterTheBreakerOpensTheNext10RequestsCauseNoCallsToThePrimary() {
        openThePrimaryBreaker();

        for (int i = 0; i < 10; i++) {
            RequestContext ctx = ctx(OPUS);
            Sink sink = run(ctx);
            assertThat(sink.completed()).isTrue();
            // No adapter call, so no attempt either: only the fallback's.
            assertThat(ctx.attempts()).singleElement().satisfies(a -> {
                assertThat(a.provider()).isEqualTo("ollama");
                assertThat(a.fallback()).isTrue();
            });
        }

        assertThat(claude.calls()).isEqualTo(20);
        assertThat(ollama.calls()).isEqualTo(20);
        assertThat(fx.breaker("claude").getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(10);
    }

    @Test
    void ac_t03_5_afterTheOpenPeriodOneTrialCallGoesToThePrimaryAndSuccessClosesTheBreaker() {
        openThePrimaryBreaker();

        fx.clock.advance(Duration.ofSeconds(29));
        run(ctx(OPUS));
        assertThat(claude.calls()).as("still open before 30 s").isEqualTo(20);

        fx.clock.advance(Duration.ofSeconds(1).plusMillis(1));
        claude.enqueue(FakeLlmProvider.reply("back"));
        RequestContext ctx = ctx(OPUS);
        Sink sink = run(ctx);

        assertThat(claude.calls()).isEqualTo(21);
        assertThat(sink.text).hasToString("back");
        assertThat(ctx.fallbackUsed()).isFalse();
        assertThat(fx.breaker("claude").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenTrialFailureOpensTheBreakerAgainWithoutRetryingThroughIt() {
        openThePrimaryBreaker();
        fx.clock.advance(Duration.ofSeconds(31));

        RequestContext ctx = ctx(OPUS);
        Sink sink = run(ctx);

        // The trial failed; the retry is refused by the re-opened breaker and the fallback answers.
        assertThat(claude.calls()).isEqualTo(21);
        assertThat(sink.completed()).isTrue();
        assertThat(ctx.fallbackUsed()).isTrue();
        assertThat(fx.breaker("claude").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void ac_t03_5_onlyOneTrialCallGoesThroughWhileHalfOpen() throws Exception {
        openThePrimaryBreaker();
        fx.clock.advance(Duration.ofSeconds(31));
        var release = new CountDownLatch(1);
        claude.enqueue(FakeLlmProvider.script(new FakeLlmProvider.Await(release), new FakeLlmProvider.Token("trial"),
                new FakeLlmProvider.Complete(new Completion(FinishReason.STOP, new Usage(1, 1), OPUS))));
        RequestContext trialCtx = ctx(OPUS);
        var trialSink = new Sink();
        Thread trial = Thread.ofVirtual().start(() -> fx.router.call(trialCtx, trialSink));
        await().atMost(5, TimeUnit.SECONDS).until(() -> claude.calls() == 21);
        assertThat(fx.breaker("claude").getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // While the trial is in flight, other requests are refused by the breaker and served by the fallback.
        for (int i = 0; i < 3; i++) {
            RequestContext other = ctx(OPUS);
            assertThat(run(other).completed()).isTrue();
            assertThat(other.attempts()).singleElement().satisfies(a -> assertThat(a.provider()).isEqualTo("ollama"));
        }
        assertThat(claude.calls()).isEqualTo(21);

        release.countDown();
        trial.join(5_000);
        assertThat(trialSink.text).hasToString("trial");
        assertThat(fx.breaker("claude").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void everyBreakerOpenIsA503WithNoAttempts() {
        fx.breaker("claude").transitionToOpenState();
        fx.breaker("ollama").transitionToOpenState();
        RequestContext ctx = ctx(OPUS);

        Sink sink = run(ctx);

        assertThat(sink.error().code()).isEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
        assertThat(sink.error().getMessage()).isEqualTo("all providers failed (claude: circuit open; ollama: circuit open)");
        assertThat(ctx.attempts()).isEmpty();
        assertThat(claude.calls() + ollama.calls()).isZero();
    }

    // ---- Retry-After (G9) ----

    @Test
    void upstreamRetryAfterReplacesTheJitterUpToItsCap() {
        var props = new FallbackProperties(CHAIN, 3, null, null, Duration.ofMillis(5), null);
        var fixture = new RouterFixture(List.of(claude, ollama), props);
        List<Duration> waits = new ArrayList<>();
        fixture.retries.retry("claude").getEventPublisher().onRetry(e -> waits.add(e.getWaitInterval()));
        claude.enqueue(fail(httpError(429, true, Duration.ofSeconds(10))))
                .enqueue(fail(httpError(429, true, Duration.ofMillis(3))))
                .enqueue(FakeLlmProvider.reply("ok"));

        var sink = new Sink();
        fixture.router.call(ctx(OPUS), sink);

        assertThat(sink.completed()).isTrue();
        // 10 s capped to the configured 5 ms; 3 ms kept. Without Retry-After the fixture's jitter would be 0.
        assertThat(waits).containsExactly(Duration.ofMillis(5), Duration.ofMillis(3));
    }

    @Test
    void maxAttemptsBoundsTheCallsPerProvider() {
        var fixture = new RouterFixture(List.of(claude, ollama), new FallbackProperties(CHAIN, 3, null, null, null, null));

        fixture.router.call(ctx(OPUS), new Sink());

        assertThat(claude.calls()).isEqualTo(3);
        assertThat(fixture.counter(MetricNames.PROVIDER_RETRIES, "provider", "claude")).isEqualTo(2);
    }

    // ---- client disconnect (C23) ----

    @Test
    void disconnectRecordsACancelledAttemptAndRethrowsWithoutRetryFallbackOrTerminalEvent() {
        claude.enqueue(FakeLlmProvider.reply("abc", "def"));
        RequestContext ctx = ctx(OPUS);
        var sink = new Sink() {
            @Override
            public void onToken(String t) {
                if (!text.isEmpty()) throw new ClientDisconnectedException(new IOException("Broken pipe"));
                super.onToken(t);
            }
        };

        assertThatThrownBy(() -> fx.router.call(ctx, sink)).isInstanceOf(ClientDisconnectedException.class);

        assertThat(sink.events).containsExactly("abc");
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(claude.cancellations()).isEqualTo(1);
        assertThat(ollama.calls()).isZero();
        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.CLIENT_CANCELLED);
            assertThat(a.usageEstimated()).isTrue();
            assertThat(a.usage().outputTokens()).isEqualTo(2);
        });
        assertThat(fx.breaker("claude").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void disconnectOnTheFinalWriteKeepsTheProviderUsage() {
        claude.enqueue(FakeLlmProvider.script(new FakeLlmProvider.Token("x"),
                new FakeLlmProvider.Complete(new Completion(FinishReason.STOP, new Usage(11, 22), OPUS))));
        RequestContext ctx = ctx(OPUS);
        var sink = new Sink() {
            @Override
            public void onComplete(Completion c) {
                throw new ClientDisconnectedException(new IOException("Broken pipe"));
            }
        };

        assertThatThrownBy(() -> fx.router.call(ctx, sink)).isInstanceOf(ClientDisconnectedException.class);

        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.CLIENT_CANCELLED);
            assertThat(a.usage()).isEqualTo(new Usage(11, 22));
            assertThat(a.usageEstimated()).isFalse();
        });
    }

    // ---- chain shape and adapter faults ----

    @Test
    void aChainEntryEqualToThePrimaryIsSkipped() {
        ollama.defaultScript(FakeLlmProvider.failBeforeFirstToken(500));
        RequestContext ctx = ctx(LLAMA);

        Sink sink = run(ctx);

        assertThat(sink.error().code()).isEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
        assertThat(ollama.calls()).isEqualTo(2);
        assertThat(ctx.attempts()).hasSize(2).noneMatch(Attempt::fallback);
        assertThat(claude.calls()).isZero();
    }

    @Test
    void aChainEntryWithoutAnAdapterIsSkipped() {
        var router = new RouterFixture(List.of(claude), CHAIN).router; // no Ollama adapter
        RequestContext ctx = ctx(OPUS);
        var sink = new Sink();

        router.call(ctx, sink);

        assertThat(sink.error().code()).isEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
        assertThat(ctx.attempts()).hasSize(2);
    }

    @Test
    void modelWithoutAnAdapterIsAnInvalidRequestWithNoAttempt() {
        var router = new RouterFixture(List.of(claude), List.of()).router;
        RequestContext ctx = ctx(LLAMA);
        var sink = new Sink();

        router.call(ctx, sink);

        assertThat(sink.error().code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ctx.attempts()).isEmpty();
    }

    @Test
    void adapterReturningWithoutCompletingIsA502WithoutRetryOrFallback() {
        claude.enqueue(FakeLlmProvider.script(new FakeLlmProvider.Token("half")));
        RequestContext ctx = ctx(OPUS);

        Sink sink = run(ctx);

        assertThat(sink.error().code()).isEqualTo(ErrorCode.PROVIDER_ERROR);
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(ollama.calls()).isZero();
        assertThat(ctx.attempts()).singleElement()
                .satisfies(a -> assertThat(a.errorKind()).isEqualTo("MALFORMED_RESPONSE"));
    }

    @Test
    void theClientErrorNeverEchoesTheProviderBody() {
        var e = FallbackRouter.toGatewayException(claude, new ProviderException(Kind.HTTP_STATUS,
                "upstream said: secret prompt", false, 400, null, null));
        assertThat(e.getMessage()).isEqualTo("provider claude rejected the request (HTTP 400)");
        var auth = FallbackRouter.toGatewayException(claude, httpError(401, false, null));
        assertThat(auth.code()).isEqualTo(ErrorCode.PROVIDER_ERROR);
    }

    // ---- startup check of the chain ----

    @Test
    void chainCheckRejectsUnknownModelsAndProviderMismatchesAndWarnsOnMissingAdapters() {
        Map<String, ModelProperties> catalog = Map.of(
                OPUS, new ModelProperties("claude", OPUS, BigDecimal.ONE, BigDecimal.ONE),
                LLAMA, new ModelProperties("ollama", LLAMA, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(ResilienceConfig.checkChain(CHAIN, catalog, List.of(claude, ollama))).isEmpty();
        assertThat(ResilienceConfig.checkChain(CHAIN, catalog, List.of(claude))).singleElement()
                .asString().contains("no adapter serves ollama/" + LLAMA);
        assertThatThrownBy(() -> ResilienceConfig.checkChain(List.of(new ChainEntry("ollama", "llama9")), catalog,
                List.of(ollama))).isInstanceOf(IllegalStateException.class).hasMessageContaining("'llama9' is not in");
        assertThatThrownBy(() -> ResilienceConfig.checkChain(List.of(new ChainEntry("claude", LLAMA)), catalog,
                List.of(claude))).isInstanceOf(IllegalStateException.class).hasMessageContaining("belongs to provider");
    }
}
