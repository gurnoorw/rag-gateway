package dev.raggateway.provider;

import dev.raggateway.core.ChatRequest;
import dev.raggateway.pipeline.StreamSink;

/**
 * One adapter per vendor (LLD). {@link #stream} blocks on the caller's virtual thread.
 *
 * <p>Contract:
 * <ul>
 *   <li>Success: calls {@code sink.onToken} for each non-empty text delta, then {@code sink.onComplete}
 *       exactly once with provider-reported usage and finish reason, then returns.</li>
 *   <li>Failure: throws {@link ProviderException}; never calls {@code sink.onError}, so the router can
 *       decide on retry or fallback. A stream that ends without a finish reason or usage is a failure
 *       ({@link ProviderException.Kind#MALFORMED_RESPONSE}).</li>
 *   <li>Timeouts are enforced inside the adapter: connect, first token (measured to the first content
 *       event, DECISIONS.md D-06), and idle gap between events. A timeout is a retryable failure.</li>
 *   <li>Cancellation: if {@code sink.onToken} throws
 *       {@link dev.raggateway.pipeline.ClientDisconnectedException}, close the upstream response (this
 *       cancels the provider call) and rethrow it unchanged; never wrap it.</li>
 *   <li>The adapter does not retry; the SDK's own retries are turned off.</li>
 * </ul>
 */
public interface LlmProvider {

    /** Provider name as used in config, logs, metrics and headers, e.g. {@code claude}, {@code ollama}. */
    String name();

    /** True if this provider serves the given public model name. */
    boolean supports(String model);

    void stream(ChatRequest request, StreamSink sink) throws ProviderException;
}
