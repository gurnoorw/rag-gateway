package dev.raggateway.pipeline;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract test of the LLD filter chain and the "settle in finally" rule (pipeline package doc). */
class FilterChainTest {

    static RequestContext ctx() {
        var tenant = new Tenant("t1", "Tenant 1", 10_000, BigDecimal.TEN, Set.of());
        var caller = new ApiKeyPrincipal("k1", tenant, Set.of("eng"), false);
        var request = new ChatRequest("claude-opus-5-5", List.of(new Message(Role.USER, "hi")), 16,
                0.0, null, List.of(), true, false, null);
        return new RequestContext("req-1", System.nanoTime(), caller, request);
    }

    static class RecordingSink implements StreamSink {
        final List<String> events = new ArrayList<>();

        @Override
        public void onToken(String text) {
            events.add("token:" + text);
        }

        @Override
        public void onComplete(Completion c) {
            events.add("complete");
        }

        @Override
        public void onError(Throwable e) {
            events.add("error");
        }
    }

    @Test
    void filtersRunInOrderThenTerminalAndFinallyRunsAfterCompletion() {
        List<String> trace = new ArrayList<>();
        GatewayFilter first = (c, s, chain) -> {
            trace.add("first-before");
            try {
                chain.next(c, s);
            } finally {
                trace.add("first-finally");
            }
        };
        GatewayFilter second = (c, s, chain) -> {
            trace.add("second");
            chain.next(c, new ForwardingSink(s) {
                @Override
                public void onToken(String text) {
                    trace.add("observed:" + text);
                    super.onToken(text);
                }
            });
        };
        Terminal terminal = (c, s) -> {
            trace.add("terminal");
            s.onToken("hi");
            s.onComplete(new Completion(FinishReason.STOP, new Usage(1, 1), "m"));
        };
        var sink = new RecordingSink();

        new FilterChain(List.of(first, second), terminal).next(ctx(), sink);

        assertThat(trace).containsExactly("first-before", "second", "terminal", "observed:hi", "first-finally");
        assertThat(sink.events).containsExactly("token:hi", "complete");
    }

    @Test
    void rejectingFilterStopsTheChain() {
        List<String> trace = new ArrayList<>();
        GatewayFilter reject = (c, s, chain) -> {
            throw new GatewayException(ErrorCode.RATE_LIMITED, "no");
        };
        Terminal terminal = (c, s) -> trace.add("terminal");

        assertThatThrownBy(() -> new FilterChain(List.of(reject), terminal).next(ctx(), new RecordingSink()))
                .isInstanceOf(GatewayException.class);
        assertThat(trace).isEmpty();
    }

    @Test
    void disconnectPropagatesThroughDecoratorsAndFinallyStillRuns() {
        List<String> trace = new ArrayList<>();
        GatewayFilter settling = (c, s, chain) -> {
            try {
                chain.next(c, new ForwardingSink(s));
            } finally {
                trace.add("settled");
            }
        };
        Terminal terminal = (c, s) -> s.onToken("x");
        StreamSink goneClient = new RecordingSink() {
            @Override
            public void onToken(String text) {
                throw new ClientDisconnectedException(new IOException("broken pipe"));
            }
        };

        assertThatThrownBy(() -> new FilterChain(List.of(settling), terminal).next(ctx(), goneClient))
                .isInstanceOf(ClientDisconnectedException.class);
        assertThat(trace).containsExactly("settled");
    }
}
