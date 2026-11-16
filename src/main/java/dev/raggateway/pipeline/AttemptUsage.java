package dev.raggateway.pipeline;

import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.core.Usage;
import dev.raggateway.provider.ProviderException;

import java.util.Objects;

/**
 * The usage recorded for one provider attempt (DECISIONS.md C8, C23, D-34). One rule for every terminal
 * (t01 direct call, t03 router, t07 validating retry), so what a tenant is billed never depends on which
 * terminal ran:
 * <ol>
 *   <li>the provider reported usage ({@code completion != null}): that usage, not estimated;</li>
 *   <li>succeeded without a completion (adapter contract breach, treated elsewhere): zero;</li>
 *   <li>an HTTP error status before the first token: zero, not estimated (providers do not bill errors);</li>
 *   <li>anything else (timeout or broken connection before the first token, failure or cancel mid-stream):
 *       the input estimate plus the estimate of the text streamed so far, flagged as estimated.</li>
 * </ol>
 *
 * @param usage     tokens to bill for the attempt
 * @param estimated true when {@code usage} is an estimate, not provider-reported
 */
public record AttemptUsage(Usage usage, boolean estimated) {

    public AttemptUsage {
        Objects.requireNonNull(usage, "usage");
    }

    /**
     * @param outcome      how the attempt ended
     * @param errorKind    {@link ProviderException.Kind} name for a provider failure, else null
     * @param completion   the completion the provider reported, or null
     * @param request      the request sent in this attempt (its model may be a fallback model)
     * @param streamedText text the attempt delivered before it ended
     */
    public static AttemptUsage of(AttemptOutcome outcome, String errorKind, Completion completion,
                                  ChatRequest request, String streamedText, TokenEstimator estimator) {
        if (completion != null) return new AttemptUsage(completion.usage(), false);
        if (outcome == AttemptOutcome.SUCCEEDED || billedNothing(outcome, errorKind)) {
            return new AttemptUsage(Usage.ZERO, false);
        }
        return new AttemptUsage(new Usage(estimator.estimateInput(request), estimator.estimate(streamedText)), true);
    }

    /** An HTTP error status before any token: the provider answered with an error and bills nothing (D-34). */
    public static boolean billedNothing(AttemptOutcome outcome, String errorKind) {
        return outcome == AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN
                && ProviderException.Kind.HTTP_STATUS.name().equals(errorKind);
    }
}
