package dev.raggateway.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Context bookkeeping that later tasks rely on (t07 round prep). */
class RequestContextTest {

    static RequestContext ctx() {
        var tenant = new Tenant("t1", "T1", 10_000, BigDecimal.TEN, Set.of());
        var request = new ChatRequest("claude-haiku-5-5", List.of(new Message(Role.USER, "q")), 64, 0.0, null,
                List.of(), false, false, "{\"type\":\"object\"}");
        return new RequestContext("r1", 0, new ApiKeyPrincipal("k1", tenant, Set.of(), false), request);
    }

    @Test
    void markingTheLastAttemptInvalidKeepsItsUsageSoItIsStillBilled() {
        var ctx = ctx();
        ctx.recordAttempt(new Attempt("claude", "claude-haiku-5-5", false, AttemptOutcome.SUCCEEDED, new Usage(10, 4),
                false, 100, 20, null));

        ctx.markLastAttemptInvalid("SCHEMA");
        ctx.recordAttempt(new Attempt("claude", "claude-haiku-5-5", false, AttemptOutcome.SUCCEEDED, new Usage(10, 5),
                false, 100, 20, null));

        assertThat(ctx.attempts()).extracting(Attempt::outcome)
                .containsExactly(AttemptOutcome.INVALID_OUTPUT, AttemptOutcome.SUCCEEDED);
        assertThat(ctx.attempts().get(0).errorKind()).isEqualTo("SCHEMA");
        assertThat(ctx.billedUsage()).isEqualTo(new Usage(20, 9));
        assertThat(ctx.servingAttempt().orElseThrow().outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
    }

    @Test
    void markingWithoutAnAttemptIsABug() {
        assertThatThrownBy(() -> ctx().markLastAttemptInvalid("SCHEMA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waitsAddUpAndIgnoreNonPositiveValues() {
        var ctx = ctx();
        ctx.addWaitNanos(5);
        ctx.addWaitNanos(-3);
        ctx.addWaitNanos(7);
        assertThat(ctx.waitNanos()).isEqualTo(12);
    }
}
