package dev.raggateway.pipeline;

import dev.raggateway.core.RequestContext;

/**
 * The last step: calls providers (LLD). Implementations over time: a direct single-provider call (t01),
 * the fallback router with retry and circuit breakers (t03), and the output-validating decorator around
 * the router (t07). Exactly one bean of this type is primary.
 *
 * <p>Contract:
 * <ul>
 *   <li>records every provider attempt with {@link RequestContext#recordAttempt};</li>
 *   <li>emits exactly one terminal event on {@code sink} (complete, or error as a
 *       {@link dev.raggateway.error.GatewayException}) and does not throw it;</li>
 *   <li>lets {@link ClientDisconnectedException} propagate after recording a
 *       {@code CLIENT_CANCELLED} attempt with estimated usage; it then emits no terminal event.</li>
 * </ul>
 */
public interface Terminal {

    void call(RequestContext ctx, StreamSink sink);
}
