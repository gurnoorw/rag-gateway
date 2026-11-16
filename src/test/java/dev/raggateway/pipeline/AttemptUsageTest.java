package dev.raggateway.pipeline;

import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.Role;
import dev.raggateway.core.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The one billing rule for provider attempts (D-34), as every terminal must apply it. */
class AttemptUsageTest {

    static final ChatRequest REQUEST = new ChatRequest("claude-haiku-5-5",
            List.of(new Message(Role.USER, "a prompt of some length")), 100, null, null, List.of(), true, false, null);
    static final CharRatioTokenEstimator ESTIMATOR = new CharRatioTokenEstimator();

    @Test
    void providerReportedUsageWins() {
        var done = new Completion(FinishReason.STOP, new Usage(7, 3), "m");
        assertThat(AttemptUsage.of(AttemptOutcome.CLIENT_CANCELLED, "CLIENT_CANCELLED", done, REQUEST, "abc", ESTIMATOR))
                .isEqualTo(new AttemptUsage(new Usage(7, 3), false));
    }

    @Test
    void httpErrorBeforeFirstTokenBillsNothing() {
        assertThat(AttemptUsage.of(AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, "HTTP_STATUS", null, REQUEST, "", ESTIMATOR))
                .isEqualTo(new AttemptUsage(Usage.ZERO, false));
    }

    @Test
    void timeoutOrConnectionBeforeFirstTokenBillsTheEstimatedInput() {
        for (String kind : List.of("FIRST_TOKEN_TIMEOUT", "CONNECT_TIMEOUT", "CONNECTION", "MALFORMED_RESPONSE")) {
            AttemptUsage u = AttemptUsage.of(AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN, kind, null, REQUEST, "", ESTIMATOR);
            assertThat(u.estimated()).as(kind).isTrue();
            assertThat(u.usage()).as(kind).isEqualTo(new Usage(ESTIMATOR.estimateInput(REQUEST), 0));
        }
    }

    @Test
    void midStreamFailureBillsInputPlusStreamedText() {
        AttemptUsage u = AttemptUsage.of(AttemptOutcome.FAILED_MID_STREAM, "CONNECTION", null, REQUEST, "streamed text", ESTIMATOR);
        assertThat(u).isEqualTo(new AttemptUsage(
                new Usage(ESTIMATOR.estimateInput(REQUEST), ESTIMATOR.estimate("streamed text")), true));
    }
}
