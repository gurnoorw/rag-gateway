package dev.raggateway.rag.query;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CollectingSinkTest {

    static RequestContext ctx() {
        var caller = new ApiKeyPrincipal("k", new Tenant("t", "T", 1000, BigDecimal.ONE, Set.of()), Set.of(), false);
        var request = new ChatRequest("auto", List.of(new Message(Role.USER, "q")), 10, 0.0, null, List.of(), false,
                false, null);
        return new RequestContext("r", 0, caller, request);
    }

    @Test
    void collectsTokensAndMarksTheFirstTokenLikeTheJsonSink() {
        RequestContext ctx = ctx();
        var sink = new CollectingSink(ctx);
        assertThat(ctx.firstTokenSent()).isFalse();

        sink.onToken("Hel");
        sink.onToken("lo");
        var completion = new Completion(FinishReason.STOP, new Usage(1, 2), "m");
        sink.onComplete(completion);

        assertThat(ctx.firstTokenSent()).as("no retry after the first token, as for chat stream:false").isTrue();
        assertThat(sink.text()).isEqualTo("Hello");
        assertThat(sink.completion()).isSameAs(completion);
        assertThat(sink.error()).isNull();
        assertThat(sink.terminated()).isTrue();
    }

    @Test
    void onlyTheFirstTerminalEventCounts() {
        var sink = new CollectingSink(ctx());
        var error = new IllegalStateException("x");

        sink.onError(error);
        sink.onToken("late");
        sink.onComplete(new Completion(FinishReason.STOP, Usage.ZERO, "m"));

        assertThat(sink.error()).isSameAs(error);
        assertThat(sink.completion()).isNull();
        assertThat(sink.text()).isEmpty();
    }
}
