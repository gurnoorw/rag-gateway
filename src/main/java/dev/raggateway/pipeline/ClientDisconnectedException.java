package dev.raggateway.pipeline;

/**
 * Thrown by the client-facing sink when a write fails because the client closed the connection.
 * It travels back through the sinks into the provider adapter, which closes its upstream connection
 * (cancels the provider call) and rethrows it. It is never a provider failure: no retry, no fallback,
 * no circuit-breaker failure (DECISIONS.md C23). Unchecked so it can cross {@link StreamSink} calls.
 */
public final class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(Throwable cause) {
        super("client disconnected", cause, false, false);
    }
}
