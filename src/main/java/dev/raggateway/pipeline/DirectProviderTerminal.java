package dev.raggateway.pipeline;

import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.provider.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * t01 {@link Terminal}: one attempt on the one provider that serves the request's model. No retry and no
 * fallback; the t03 router replaces it.
 *
 * <ul>
 *   <li>Success: records a {@link AttemptOutcome#SUCCEEDED} attempt with provider-reported usage.</li>
 *   <li>{@link ProviderException}: records a failed attempt (before or after the first token) with estimated
 *       usage, then emits {@code onError}: a non-retryable upstream 4xx is {@link ErrorCode#UPSTREAM_REJECTED}
 *       (400), anything else {@link ErrorCode#PROVIDER_ERROR} (502).</li>
 *   <li>{@link ClientDisconnectedException}: records a {@link AttemptOutcome#CLIENT_CANCELLED} attempt with
 *       estimated usage (input estimate plus the text streamed so far, C23) and rethrows it.</li>
 * </ul>
 */
public final class DirectProviderTerminal implements Terminal {

    private static final Logger log = LoggerFactory.getLogger(DirectProviderTerminal.class);

    private final List<LlmProvider> providers;
    private final TokenEstimator estimator;
    private final LongSupplier nanoClock;

    public DirectProviderTerminal(List<LlmProvider> providers, TokenEstimator estimator) {
        this(providers, estimator, System::nanoTime);
    }

    DirectProviderTerminal(List<LlmProvider> providers, TokenEstimator estimator, LongSupplier nanoClock) {
        this.providers = List.copyOf(providers);
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public void call(RequestContext ctx, StreamSink sink) {
        ChatRequest request = ctx.request();
        LlmProvider provider = providers.stream().filter(p -> p.supports(request.model())).findFirst().orElse(null);
        if (provider == null) {
            // Model selection accepted a catalog model whose provider has no adapter yet (e.g. Ollama before t03).
            sink.onError(new GatewayException(ErrorCode.INVALID_REQUEST,
                    "model '" + request.model() + "' is not served by any configured provider"));
            return;
        }

        ctx.startAttempt(provider.name(), request.model(), false);
        var observer = new AttemptSink(sink, nanoClock);
        long start = nanoClock.getAsLong();
        try {
            provider.stream(request, observer);
        } catch (ClientDisconnectedException e) {
            ctx.recordAttempt(attempt(provider, request, AttemptOutcome.CLIENT_CANCELLED, observer, start,
                    "CLIENT_CANCELLED"));
            throw e;
        } catch (ProviderException e) {
            AttemptOutcome outcome = observer.tokens > 0 ? AttemptOutcome.FAILED_MID_STREAM
                    : AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN;
            ctx.recordAttempt(attempt(provider, request, outcome, observer, start, e.kind().name()));
            log.warn("provider {} failed: kind={} status={} retryable={}", provider.name(), e.kind(),
                    e.httpStatus(), e.retryable());
            sink.onError(toGatewayException(provider, e));
            return;
        } catch (RuntimeException e) {
            // An adapter bug, not an upstream answer: still one attempt, still a terminal event.
            ctx.recordAttempt(attempt(provider, request, observer.tokens > 0 ? AttemptOutcome.FAILED_MID_STREAM
                    : AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, observer, start, e.getClass().getSimpleName()));
            log.error("provider {} adapter threw unexpectedly", provider.name(), e);
            sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "provider " + provider.name() + " failed", e));
            return;
        }

        if (observer.completion == null) {
            // Contract breach by the adapter: it returned without onComplete.
            ctx.recordAttempt(attempt(provider, request, observer.tokens > 0 ? AttemptOutcome.FAILED_MID_STREAM
                    : AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, observer, start, "MALFORMED_RESPONSE"));
            sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR,
                    "provider " + provider.name() + " ended the stream without completing"));
            return;
        }
        ctx.recordAttempt(attempt(provider, request, AttemptOutcome.SUCCEEDED, observer, start, null));
    }

    static GatewayException toGatewayException(LlmProvider provider, ProviderException e) {
        int status = e.httpStatus();
        // 401/403 from the provider mean the gateway's own credential is wrong or lacks access: the caller cannot
        // fix that, so it is a 502, not the caller's 400 (D-30).
        if (!e.retryable() && status >= 400 && status < 500 && status != 401 && status != 403) {
            return new GatewayException(ErrorCode.UPSTREAM_REJECTED,
                    "provider " + provider.name() + " rejected the request (HTTP " + status + ")", e);
        }
        String detail = status > 0 ? "HTTP " + status : e.kind().name().toLowerCase(Locale.ROOT);
        return new GatewayException(ErrorCode.PROVIDER_ERROR, "provider " + provider.name() + " failed (" + detail + ")", e);
    }

    private Attempt attempt(LlmProvider provider, ChatRequest request, AttemptOutcome outcome, AttemptSink observer,
                            long start, String errorKind) {
        long now = nanoClock.getAsLong();
        Usage usage;
        boolean estimated;
        if (observer.completion != null) {
            // The provider reported usage before the failure (e.g. the final write to the client failed).
            usage = observer.completion.usage();
            estimated = false;
        } else if (outcome == AttemptOutcome.SUCCEEDED) {
            usage = Usage.ZERO;
            estimated = false;
        } else {
            usage = new Usage(estimator.estimateInput(request), estimator.estimate(observer.text.toString()));
            estimated = true;
        }
        long firstToken = observer.firstTokenNanos < 0 ? -1 : observer.firstTokenNanos - start;
        return new Attempt(provider.name(), request.model(), false, outcome, usage, estimated, now - start,
                firstToken, errorKind);
    }

    /** Observes one attempt: streamed text (for estimated usage), first-token time, completion. */
    private static final class AttemptSink extends ForwardingSink {
        private final LongSupplier nanoClock;
        private final StringBuilder text = new StringBuilder();
        private int tokens;
        private long firstTokenNanos = -1;
        private Completion completion;

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
