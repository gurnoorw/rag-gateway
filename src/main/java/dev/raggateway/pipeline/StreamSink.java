package dev.raggateway.pipeline;

import dev.raggateway.core.Completion;

/**
 * Receives a response piece by piece (LLD). Called only from the request's own thread.
 *
 * <p>Contract: zero or more {@link #onToken} calls, then exactly one terminal call, either
 * {@link #onComplete} or {@link #onError}. The client-facing sink decides the transport from whether
 * anything was written yet (DECISIONS.md C15): an error before the first token becomes an HTTP status
 * with a JSON body, an error after it becomes an SSE error event.
 *
 * <p>If the client has gone away, the client-facing sink throws {@link ClientDisconnectedException}
 * from {@link #onToken}; decorators must let it propagate so the provider adapter can cancel upstream.
 */
public interface StreamSink {

    /** A non-empty piece of generated text. */
    void onToken(String text);

    /** The stream ended normally. */
    void onComplete(Completion completion);

    /** The request failed; usually a {@link dev.raggateway.error.GatewayException}. */
    void onError(Throwable error);
}
