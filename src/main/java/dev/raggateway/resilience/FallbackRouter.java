package dev.raggateway.resilience;

import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.AttemptUsage;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.ForwardingSink;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedRunnable;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * t03 {@link Terminal}: walks the provider chain with a Resilience4j retry and circuit breaker per provider
 * (DECISIONS.md C6, C20, D-07; LLD "Circuit breaker and fallback router": behaviour kept, implementation
 * Resilience4j). Replaces the t01 direct terminal.
 *
 * <p>The chain is the provider serving the selected model, then each {@code gateway.fallback.chain} entry
 * {@code {provider, model}} (an entry equal to an earlier step is skipped).
 * Each provider step runs {@code Retry(CircuitBreaker(adapter.stream))}:
 * <ul>
 *   <li><b>Retry on the same provider</b> only when {@link ProviderException#retryable()} <b>and</b> no token
 *       reached the client in that attempt (tokens cannot be un-sent). Wait: full jitter, or the upstream
 *       {@code Retry-After} up to a cap ({@link FullJitterBackoff}).</li>
 *   <li><b>Move to the next provider</b> only when {@link ProviderException#fallbackAllowed()}: a retryable
 *       failure after the retries, or at once for a provider configuration fault: 401/403/404 (wrong key, no
 *       access, model not there; another provider can still answer, D-30, D-37). An open breaker
 *       ({@link CallNotPermittedException}) also moves on at once, with no adapter call and no attempt.</li>
 *   <li><b>Stop</b> on any other failure: a non-retryable 4xx other than a configuration fault (the request itself is
 *       invalid, AC-t03-6), a failure after the first token (SSE error, AC-t03-3), or an
 *       adapter bug.</li>
 *   <li>Every step tried and failed (or skipped by an open breaker) → 503 {@code all_providers_failed}.</li>
 * </ul>
 *
 * <p>The breaker counts retryable failures (timeouts, connection errors, 429, 5xx, before or after the first
 * token) as failures; it ignores everything else: non-retryable 4xx including 401/403/404 (a configuration fault
 * that does not heal with time, so it must show up in logs and alerts rather than hide behind an open breaker),
 * client disconnects and adapter bugs. See {@link ResilienceConfig}.
 *
 * <p>Bookkeeping per adapter call, per the {@link Terminal} contract: {@link RequestContext#startAttempt} right
 * before the call (D-28; inside the breaker, so a call the breaker refuses announces nothing), then one
 * {@link Attempt} with usage from {@link AttemptUsage#of} (D-34) and {@code fallback} set on every step after the
 * first (C21).
 */
public final class FallbackRouter implements Terminal {

    private static final Logger log = LoggerFactory.getLogger(FallbackRouter.class);

    private final List<LlmProvider> providers;
    private final List<Step> chain;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final TokenEstimator estimator;
    private final MeterRegistry meters;
    private final LongSupplier nanoClock;

    /**
     * @param providers adapters, in bean order; the first that supports the selected model is the primary
     * @param chain     fallback steps after the primary (C20); each must have an adapter, else this throws
     * @param breakers  registry whose default config is {@link ResilienceConfig#breakerConfig}; one breaker per
     *                  provider name
     * @param retries   registry whose default config is {@link ResilienceConfig#retryConfig}; one retry per
     *                  provider name
     */
    public FallbackRouter(List<LlmProvider> providers, List<ChainEntry> chain, CircuitBreakerRegistry breakers,
                          RetryRegistry retries, TokenEstimator estimator, MeterRegistry meters) {
        this(providers, chain, breakers, retries, estimator, meters, System::nanoTime);
    }

    FallbackRouter(List<LlmProvider> providers, List<ChainEntry> chain, CircuitBreakerRegistry breakers,
                   RetryRegistry retries, TokenEstimator estimator, MeterRegistry meters, LongSupplier nanoClock) {
        this.providers = List.copyOf(providers);
        this.chain = chain.stream().map(e -> new Step(this.providers.stream()
                .filter(p -> p.name().equals(e.provider()) && p.supports(e.model())).findFirst()
                .orElseThrow(() -> new IllegalStateException("gateway.fallback.chain: no adapter serves "
                        + e.provider() + "/" + e.model())), e.model())).toList();
        this.breakers = Objects.requireNonNull(breakers, "breakers");
        this.retries = Objects.requireNonNull(retries, "retries");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public void call(RequestContext ctx, StreamSink sink) {
        ChatRequest request = ctx.request();
        LlmProvider primary = providers.stream().filter(p -> p.supports(request.model())).findFirst().orElse(null);
        if (primary == null) {
            // Unreachable once the startup catalog check (t03, D-30) runs: every catalog model has an adapter.
            sink.onError(new GatewayException(ErrorCode.INVALID_REQUEST,
                    "model '" + request.model() + "' is not served by any configured provider"));
            return;
        }

        List<Step> steps = steps(primary, request.model());
        List<String> failures = new ArrayList<>();
        Throwable lastCause = null;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (i > 0) {
                Counter.builder(MetricNames.PROVIDER_FALLBACKS)
                        .description("Moves to the next provider in the fallback chain")
                        .tag("from_provider", steps.get(i - 1).provider().name())
                        .tag("to_provider", step.provider().name())
                        .register(meters).increment();
            }
            StepResult result = runStep(ctx, sink, step, i > 0);
            switch (result) {
                case StepResult.Succeeded s -> {
                    return;
                }
                case StepResult.Stop stop -> {
                    sink.onError(stop.error());
                    return;
                }
                case StepResult.MoveOn moveOn -> {
                    failures.add(step.provider().name() + ": " + moveOn.reason());
                    if (moveOn.cause() != null) lastCause = moveOn.cause();
                }
            }
        }
        sink.onError(new GatewayException(ErrorCode.ALL_PROVIDERS_FAILED,
                "all providers failed (" + String.join("; ", failures) + ")", lastCause));
    }

    /** The primary, then each chain entry, without repeats. */
    private List<Step> steps(LlmProvider primary, String model) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(primary, model));
        for (Step next : chain) {
            boolean repeat = steps.stream().anyMatch(x -> x.provider() == next.provider() && x.model().equals(next.model()));
            if (!repeat) steps.add(next);
        }
        return steps;
    }

    private StepResult runStep(RequestContext ctx, StreamSink sink, Step step, boolean fallback) {
        LlmProvider provider = step.provider();
        ChatRequest request = ctx.request().model().equals(step.model()) ? ctx.request()
                : ctx.request().withModel(step.model());
        CircuitBreaker breaker = breakers.circuitBreaker(provider.name());
        Retry retry = retries.retry(provider.name());
        int[] attemptNumber = {0};
        long[] lastAttemptEnd = {-1};
        CheckedRunnable attempt = () -> {
            // The gap since the previous attempt on this provider is the retry backoff: a deliberate wait, not
            // gateway work, so it is excluded from gateway overhead (D-39).
            if (lastAttemptEnd[0] >= 0) ctx.addWaitNanos(nanoClock.getAsLong() - lastAttemptEnd[0]);
            try {
                runAttempt(ctx, sink, provider, request, fallback, ++attemptNumber[0]);
            } finally {
                lastAttemptEnd[0] = nanoClock.getAsLong();
            }
        };
        try {
            Retry.decorateCheckedRunnable(retry, CircuitBreaker.decorateCheckedRunnable(breaker, attempt)).run();
            return new StepResult.Succeeded();
        } catch (CallNotPermittedException e) {
            log.warn("provider {} skipped: circuit breaker {}", provider.name(), breaker.getState());
            return new StepResult.MoveOn("circuit open", null);
        } catch (AttemptFailed f) {
            return afterFailure(provider, f);
        } catch (RuntimeException | Error e) {
            throw e; // ClientDisconnectedException (attempt already recorded) or a bug outside the adapter call
        } catch (Throwable t) {
            throw new IllegalStateException("unexpected checked exception from the provider step", t);
        }
    }

    private StepResult afterFailure(LlmProvider provider, AttemptFailed f) {
        if (f.error == null) return new StepResult.Stop(f.clientError);
        ProviderException e = f.error;
        if (f.tokensSent) {
            // Mid-stream: the client already has text; a retry or fallback would repeat it (AC-t03-3).
            return new StepResult.Stop(new GatewayException(ErrorCode.PROVIDER_ERROR,
                    "provider " + provider.name() + " failed mid-stream (" + detail(e) + ")", e));
        }
        if (e.fallbackAllowed()) return new StepResult.MoveOn(detail(e), e);
        return new StepResult.Stop(toGatewayException(provider, e));
    }

    /** One adapter call: announce, stream through an observer, record the attempt, classify a failure. */
    private void runAttempt(RequestContext ctx, StreamSink sink, LlmProvider provider, ChatRequest request,
                            boolean fallback, int attemptNumber) throws AttemptFailed {
        if (attemptNumber > 1) {
            Counter.builder(MetricNames.PROVIDER_RETRIES).description("Retries on the same provider")
                    .tag("provider", provider.name()).register(meters).increment();
        }
        ctx.startAttempt(provider.name(), request.model(), fallback);
        var observer = new AttemptSink(sink, nanoClock);
        long start = nanoClock.getAsLong();
        try {
            provider.stream(request, observer);
        } catch (ClientDisconnectedException e) {
            ctx.recordAttempt(attempt(provider, request, fallback, AttemptOutcome.CLIENT_CANCELLED, observer, start,
                    "CLIENT_CANCELLED"));
            throw e;
        } catch (ProviderException e) {
            observer.reportedUsage = e.reportedUsage().orElse(null); // e.g. an empty answer the provider billed (D-44)
            ctx.recordAttempt(attempt(provider, request, fallback, failedOutcome(observer), observer, start,
                    e.kind().name()));
            log.warn("provider {} attempt {} failed: kind={} status={} retryable={} fallbackAllowed={} tokensSent={}",
                    provider.name(), attemptNumber, e.kind(), e.httpStatus(), e.retryable(), e.fallbackAllowed(),
                    observer.tokens > 0);
            throw new AttemptFailed(e, observer.tokens > 0, null);
        } catch (RuntimeException e) {
            // An adapter bug, not an upstream answer: still one attempt, no retry, no fallback.
            ctx.recordAttempt(attempt(provider, request, fallback, failedOutcome(observer), observer, start,
                    e.getClass().getSimpleName()));
            log.error("provider {} adapter threw unexpectedly", provider.name(), e);
            throw new AttemptFailed(null, observer.tokens > 0,
                    new GatewayException(ErrorCode.PROVIDER_ERROR, "provider " + provider.name() + " failed", e));
        }
        if (observer.completion == null) {
            // Contract breach by the adapter: it returned without onComplete.
            ctx.recordAttempt(attempt(provider, request, fallback, failedOutcome(observer), observer, start,
                    "MALFORMED_RESPONSE"));
            throw new AttemptFailed(null, observer.tokens > 0, new GatewayException(ErrorCode.PROVIDER_ERROR,
                    "provider " + provider.name() + " ended the stream without completing"));
        }
        ctx.recordAttempt(attempt(provider, request, fallback, AttemptOutcome.SUCCEEDED, observer, start, null));
    }

    private static AttemptOutcome failedOutcome(AttemptSink observer) {
        return observer.tokens > 0 ? AttemptOutcome.FAILED_MID_STREAM : AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN;
    }

    private Attempt attempt(LlmProvider provider, ChatRequest request, boolean fallback, AttemptOutcome outcome,
                            AttemptSink observer, long start, String errorKind) {
        long now = nanoClock.getAsLong();
        AttemptUsage billed = AttemptUsage.of(outcome, errorKind,
                observer.completion != null ? observer.completion.usage() : observer.reportedUsage, request,
                observer.text.toString(), estimator);
        long firstToken = observer.firstTokenNanos < 0 ? -1 : observer.firstTokenNanos - start;
        return new Attempt(provider.name(), request.model(), fallback, outcome, billed.usage(), billed.estimated(),
                now - start, firstToken, errorKind);
    }

    /**
     * Client-facing error for a failure that stops the chain before the first token. A non-retryable 4xx that is
     * not a configuration fault (401/403/404) is the caller's request ({@link ErrorCode#UPSTREAM_REJECTED},
     * 400); anything else is
     * {@link ErrorCode#PROVIDER_ERROR} (502). The provider's response body is never echoed (it may quote the
     * prompt).
     */
    static GatewayException toGatewayException(LlmProvider provider, ProviderException e) {
        int status = e.httpStatus();
        if (!e.retryable() && status >= 400 && status < 500 && !e.configurationFault()) {
            return new GatewayException(ErrorCode.UPSTREAM_REJECTED,
                    "provider " + provider.name() + " rejected the request (HTTP " + status + ")", e);
        }
        return new GatewayException(ErrorCode.PROVIDER_ERROR, "provider " + provider.name() + " failed (" + detail(e) + ")", e);
    }

    private static String detail(ProviderException e) {
        return e.httpStatus() > 0 ? "HTTP " + e.httpStatus() : e.kind().name().toLowerCase(Locale.ROOT);
    }

    // ---- predicates and wait used by the Resilience4j configs (ResilienceConfig) ----

    /** Retry predicate (D-07): a retryable provider failure with no token sent; never an open breaker. */
    static boolean retryAllowed(Throwable t) {
        return t instanceof AttemptFailed f && f.error != null && f.error.retryable() && !f.tokensSent;
    }

    /** Breaker predicate (D-07): retryable provider failures are health signals; nothing else counts. */
    static boolean countsAsProviderFailure(Throwable t) {
        return t instanceof AttemptFailed f && f.error != null && f.error.retryable();
    }

    /** Upstream {@code Retry-After} of the failed attempt, if any (G9). */
    static Optional<Duration> retryAfter(Either<Throwable, ?> outcome) {
        if (outcome != null && outcome.isLeft() && outcome.getLeft() instanceof AttemptFailed f && f.error != null) {
            return f.error.retryAfter();
        }
        return Optional.empty();
    }

    // ---- types ----

    private record Step(LlmProvider provider, String model) {
    }

    private sealed interface StepResult {
        record Succeeded() implements StepResult {
        }

        record Stop(GatewayException error) implements StepResult {
        }

        record MoveOn(String reason, Throwable cause) implements StepResult {
        }
    }

    /**
     * Ends one attempt with what the retry, the breaker and the router need: the provider failure (null for an
     * adapter bug), whether a token already reached the client, and for a bug the client-facing error.
     */
    static final class AttemptFailed extends Exception {
        final ProviderException error;
        final boolean tokensSent;
        final GatewayException clientError;

        AttemptFailed(ProviderException error, boolean tokensSent, GatewayException clientError) {
            super(error != null ? error.getMessage() : clientError.getMessage(), error != null ? error : clientError,
                    false, false);
            this.error = error;
            this.tokensSent = tokensSent;
            this.clientError = clientError;
        }
    }

    /** Observes one attempt: streamed text (for estimated usage), token count, first-token time, completion. */
    private static final class AttemptSink extends ForwardingSink {
        private final LongSupplier nanoClock;
        private final StringBuilder text = new StringBuilder();
        private int tokens;
        private long firstTokenNanos = -1;
        private Completion completion;
        private Usage reportedUsage;

        AttemptSink(StreamSink delegate, LongSupplier nanoClock) {
            super(delegate);
            this.nanoClock = nanoClock;
        }

        @Override
        public void onToken(String piece) {
            if (firstTokenNanos < 0) firstTokenNanos = nanoClock.getAsLong();
            tokens++;
            text.append(piece);
            super.onToken(piece);
        }

        @Override
        public void onComplete(Completion c) {
            completion = c;
            super.onComplete(c);
        }
    }
}
