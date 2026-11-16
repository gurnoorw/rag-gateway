package dev.raggateway.pipeline;

import dev.raggateway.core.Completion;

import java.util.Objects;

/**
 * Decorator base (LLD): forwards every call to the wrapped sink. Subclasses override one method to
 * observe the stream (buffer for the cache, detect the first token, count text) and must call
 * {@code super}. They must not swallow {@link ClientDisconnectedException}.
 */
public class ForwardingSink implements StreamSink {

    protected final StreamSink delegate;

    public ForwardingSink(StreamSink delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onToken(String text) {
        delegate.onToken(text);
    }

    @Override
    public void onComplete(Completion completion) {
        delegate.onComplete(completion);
    }

    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }
}
