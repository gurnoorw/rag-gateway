package dev.raggateway.pipeline;

import dev.raggateway.core.RequestContext;

/**
 * Runs one request through filters and terminal. Used by the chat endpoint and by the RAG service
 * for its model call, so RAG gets limits, cache, fallback, validation, logging and metrics without a
 * second copy of that logic (HLD FR5).
 *
 * <p>Contract: never throws. A {@link dev.raggateway.error.GatewayException} thrown by a filter is
 * delivered to {@code clientSink.onError}; a {@link ClientDisconnectedException} ends the request as
 * cancelled. Before returning it calls {@link RequestContext#finish} and then every
 * {@link RequestCompletionListener} (request log t01, metrics t04), catching and counting their failures.
 */
public interface GatewayPipeline {

    void run(RequestContext ctx, StreamSink clientSink);
}
