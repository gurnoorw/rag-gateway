package dev.raggateway.pipeline;

import dev.raggateway.core.RequestContext;

/**
 * The last step: calls providers (LLD). Implementations over time: a direct single-provider call (t01),
 * the fallback router with retry and circuit breakers (t03), and the output-validating decorator around
 * the router (t07). Exactly one bean of this type is primary.
 *
 * <p>Contract:
 * <ul>
 *   <li>calls {@link RequestContext#startAttempt} immediately before every adapter call, including retries
 *       and fallbacks, so the response headers committed at the first byte name the provider streaming (D-28);</li>
 *   <li>records every provider attempt with {@link RequestContext#recordAttempt}, its usage from
 *       {@link AttemptUsage#of} (one billing rule for every terminal, D-34) and {@code fallback} set when the
 *       provider is not the one model selection chose (C21);</li>
 *   <li>emits exactly one terminal event on {@code sink} (complete, or error as a
 *       {@link dev.raggateway.error.GatewayException}) and does not throw it;</li>
 *   <li>lets {@link ClientDisconnectedException} propagate after recording a
 *       {@code CLIENT_CANCELLED} attempt with estimated usage; it then emits no terminal event.</li>
 * </ul>
 */
public interface Terminal {

    void call(RequestContext ctx, StreamSink sink);
}
