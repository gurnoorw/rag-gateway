package dev.raggateway.pipeline;

import dev.raggateway.core.Completion;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@link GatewayPipeline} (LLD "The request pipeline", DECISIONS.md C3/C7/C23): runs the filters in
 * {@link FilterOrder}, then the terminal, on the caller's thread, and never throws.
 *
 * <ul>
 *   <li>A {@link GatewayException} thrown by a filter (or any other exception) is delivered to the client
 *       sink's {@code onError}, unless a terminal event already reached it.</li>
 *   <li>A {@link ClientDisconnectedException} ends the request as {@link RequestOutcome#CLIENT_CANCELLED}.</li>
 *   <li>The outcome is read from what actually reached the client sink: {@code onComplete} is success,
 *       {@code onError} is an error with its code.</li>
 *   <li>Then {@link RequestContext#finish} and every {@link RequestCompletionListener}, each exactly once;
 *       a listener that throws is logged and counted ({@code gateway.listener.failures}) and the others still run.</li>
 * </ul>
 *
 * <p>The HTTP status recorded for an error is the code's status, except for a stream whose first token was
 * already sent: the client saw 200 and an SSE error event (C15). A cancelled request records 499
 * ("client closed request", no response) when nothing was sent, else 200.
 */
public final class DefaultGatewayPipeline implements GatewayPipeline {

    private static final Logger log = LoggerFactory.getLogger(DefaultGatewayPipeline.class);

    /** Status recorded when the client went away before anything was sent (nginx convention). */
    public static final int CLIENT_CLOSED_REQUEST = 499;

    private final List<GatewayFilter> filters;
    private final Terminal terminal;
    private final List<RequestCompletionListener> listeners;
    private final MeterRegistry registry;

    /**
     * @param filters   any order; sorted here by {@code @Order} / {@code Ordered} ({@link FilterOrder})
     * @param terminal  the primary terminal
     * @param listeners called after every request, in the given order
     */
    public DefaultGatewayPipeline(List<GatewayFilter> filters, Terminal terminal,
                                  List<RequestCompletionListener> listeners, MeterRegistry registry) {
        List<GatewayFilter> sorted = new ArrayList<>(filters);
        AnnotationAwareOrderComparator.sort(sorted);
        this.filters = List.copyOf(sorted);
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.listeners = List.copyOf(listeners);
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Filters in the order they run (tests, startup log). */
    public List<GatewayFilter> filters() {
        return filters;
    }

    @Override
    public void run(RequestContext ctx, StreamSink clientSink) {
        var sink = new OutcomeSink(clientSink);
        boolean cancelled = false;
        try {
            new FilterChain(filters, terminal).next(ctx, sink);
            if (!sink.terminated()) {
                // A filter or terminal broke the contract (returned without a terminal event).
                log.error("request {} finished without a terminal event", ctx.requestId());
                sink.onError(new GatewayException(ErrorCode.INTERNAL_ERROR, "internal error"));
            }
        } catch (ClientDisconnectedException e) {
            cancelled = true;
        } catch (RuntimeException e) {
            cancelled = deliverError(ctx, sink, e);
        }
        finish(ctx, sink, cancelled);
        notifyListeners(ctx);
    }

    /** @return true when delivering the error showed that the client is gone */
    private static boolean deliverError(RequestContext ctx, OutcomeSink sink, RuntimeException e) {
        GatewayException error = e instanceof GatewayException ge ? ge : internal(ctx, e);
        if (sink.terminated()) {
            log.warn("request {} failed after its terminal event: {}", ctx.requestId(), e.toString());
            return false;
        }
        try {
            sink.onError(error);
            return false;
        } catch (ClientDisconnectedException disconnected) {
            return true;
        } catch (RuntimeException sinkFailure) {
            log.warn("request {}: client sink failed while reporting an error: {}", ctx.requestId(),
                    sinkFailure.toString());
            sink.recordError(error);
            return false;
        }
    }

    private static GatewayException internal(RequestContext ctx, RuntimeException e) {
        log.error("request {} failed with an unexpected exception", ctx.requestId(), e);
        return new GatewayException(ErrorCode.INTERNAL_ERROR, "internal error", e);
    }

    private static void finish(RequestContext ctx, OutcomeSink sink, boolean cancelled) {
        if (cancelled) {
            ctx.finish(RequestOutcome.CLIENT_CANCELLED, null, ctx.firstTokenSent() ? 200 : CLIENT_CLOSED_REQUEST);
        } else if (sink.completed()) {
            ctx.finish(RequestOutcome.SUCCESS, null, 200);
        } else {
            ErrorCode code = sink.errorCode();
            boolean sentAsSseEvent = ctx.firstTokenSent() && ctx.request().stream();
            ctx.finish(RequestOutcome.ERROR, code, sentAsSseEvent ? 200 : code.httpStatus());
        }
    }

    private void notifyListeners(RequestContext ctx) {
        for (RequestCompletionListener listener : listeners) {
            try {
                listener.onRequestFinished(ctx);
            } catch (RuntimeException e) {
                String name = listener.getClass().getSimpleName();
                registry.counter(MetricNames.LISTENER_FAILURES, "listener", name).increment();
                log.warn("completion listener {} failed for request {}: {}", name, ctx.requestId(), e.toString());
            }
        }
    }

    /** Records which terminal event reached the client sink. */
    private static final class OutcomeSink extends ForwardingSink {
        private boolean completed;
        private Throwable error;

        OutcomeSink(StreamSink delegate) {
            super(delegate);
        }

        @Override
        public void onComplete(Completion completion) {
            completed = true;
            super.onComplete(completion);
        }

        @Override
        public void onError(Throwable e) {
            error = e;
            super.onError(e);
        }

        void recordError(Throwable e) {
            error = e;
        }

        boolean terminated() {
            return completed || error != null;
        }

        boolean completed() {
            return completed && error == null;
        }

        ErrorCode errorCode() {
            return error instanceof GatewayException ge ? ge.code() : ErrorCode.INTERNAL_ERROR;
        }
    }
}
