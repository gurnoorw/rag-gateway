package dev.raggateway.rag.query;

import dev.raggateway.core.Completion;
import dev.raggateway.core.RequestContext;
import dev.raggateway.pipeline.StreamSink;

/**
 * Client sink of the RAG answer call: keeps the whole answer in memory, because citations are read from the
 * complete text and the endpoint returns one JSON body (D-49: no streaming RAG answers).
 *
 * <p>Behaves like the chat endpoint's {@code stream:false} sink towards the pipeline: the first token marks
 * {@link RequestContext#markFirstTokenSent}, so the "no retry after the first token" rule and the time-to-first-token
 * metric mean the same as for chat; only the first terminal event counts. Nothing is written to the client here;
 * {@link RagService} reads the result after {@code GatewayPipeline.run} returns.
 */
public final class CollectingSink implements StreamSink {

    private final RequestContext ctx;
    private final StringBuilder text = new StringBuilder();
    private Completion completion;
    private Throwable error;

    public CollectingSink(RequestContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onToken(String token) {
        if (terminated()) return;
        ctx.markFirstTokenSent(System.nanoTime());
        text.append(token);
    }

    @Override
    public void onComplete(Completion completion) {
        if (terminated()) return;
        this.completion = completion;
    }

    @Override
    public void onError(Throwable error) {
        if (terminated()) return;
        this.error = error == null ? new IllegalStateException("onError(null)") : error;
    }

    public boolean terminated() {
        return completion != null || error != null;
    }

    /** The answer text so far (the whole answer after {@link #onComplete}). */
    public String text() {
        return text.toString();
    }

    /** The completion, or null when the call did not complete. */
    public Completion completion() {
        return completion;
    }

    /** The error delivered by the pipeline, or null. */
    public Throwable error() {
        return error;
    }
}
