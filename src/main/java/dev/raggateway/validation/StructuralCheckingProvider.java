package dev.raggateway.validation;

import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.pipeline.ForwardingSink;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.provider.ProviderException;

import java.util.Objects;

/**
 * Structural check of one provider attempt (t07; DECISIONS.md C8, D-43). The fallback router wraps every adapter in
 * this decorator ({@code ResilienceConfig}), so a broken answer is a provider failure of that attempt: retried on the
 * same provider and then fallen back like any other failure before the first token.
 *
 * <ul>
 *   <li>Tokens pass straight through (no buffering; AC-t07-5).</li>
 *   <li>The adapter's {@code onComplete} is held back and decided on after {@code stream} returns.</li>
 *   <li>Finish reason {@code stop} with no text at all: {@link ProviderException.Kind#MALFORMED_RESPONSE},
 *       retryable, counted as {@code gateway.validation.failures{kind=structural}}. Nothing reached the client, so
 *       the retry is legal.</li>
 *   <li>Empty text with {@code length} or {@code content_filter} is a legitimate answer (a refusal, or a
 *       {@code max_tokens} too small to say anything) and passes (C8 refinement).</li>
 *   <li>A missing finish reason or usage never gets here: both adapters already throw
 *       {@code MALFORMED_RESPONSE} for it. An adapter that returns without {@code onComplete} is left to the router,
 *       which treats it as a contract breach.</li>
 * </ul>
 *
 * <p>Billing: the router never sees the held-back completion, so the rejected attempt is recorded as a failure before
 * the first token with an estimated input (D-34: the provider did process the prompt), not with the usage the
 * provider reported. The estimate is close for the input and the output was empty.
 */
public final class StructuralCheckingProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final ValidationFailures failures;

    public StructuralCheckingProvider(LlmProvider delegate, ValidationFailures failures) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /** The wrapped adapter (tests, startup log). */
    public LlmProvider delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean supports(String model) {
        return delegate.supports(model);
    }

    @Override
    public void stream(ChatRequest request, StreamSink sink) throws ProviderException {
        var held = new HoldingSink(sink);
        delegate.stream(request, held);
        Completion completion = held.completion;
        if (completion == null) return;
        if (completion.finishReason() == FinishReason.STOP && !held.sawText) {
            failures.increment(request.model(), ValidationFailures.STRUCTURAL);
            throw new ProviderException(ProviderException.Kind.MALFORMED_RESPONSE,
                    "provider " + delegate.name() + " ended with finish reason stop and no text", true, 0, null, null);
        }
        sink.onComplete(completion);
    }

    /** Forwards tokens, keeps {@code onComplete} for the decision after the adapter returns. */
    private static final class HoldingSink extends ForwardingSink {
        private boolean sawText;
        private Completion completion;

        HoldingSink(StreamSink delegate) {
            super(delegate);
        }

        @Override
        public void onToken(String text) {
            if (text != null && !text.isEmpty()) sawText = true;
            super.onToken(text);
        }

        @Override
        public void onComplete(Completion c) {
            completion = c;
        }
    }
}
