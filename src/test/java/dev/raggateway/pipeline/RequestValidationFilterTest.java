package dev.raggateway.pipeline;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestValidationFilterTest {

    private final RequestValidationFilter filter =
            new RequestValidationFilter(new GatewayProperties(1024, 100, Map.of(), Map.of()));

    static RequestContext ctx(int maxTokens) {
        var tenant = new Tenant("t1", "Tenant 1", 10_000, BigDecimal.TEN, Set.of());
        var caller = new ApiKeyPrincipal("k1", tenant, Set.of(), false);
        var request = new ChatRequest("m", List.of(new Message(Role.USER, "q")), maxTokens,
                null, null, List.of(), false, false, null);
        return new RequestContext("r1", System.nanoTime(), caller, request);
    }

    @Test
    void maxTokensAboveCapIsRejectedAndTerminalNotCalled() {
        AtomicInteger terminalCalls = new AtomicInteger();
        var chain = new FilterChain(List.of(filter), (c, s) -> terminalCalls.incrementAndGet());

        assertThatThrownBy(() -> chain.next(ctx(101), new ForwardingSink(new NoopSink())))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessageContaining("exceeds the limit of 100");
        assertThat(terminalCalls).hasValue(0);
    }

    @Test
    void maxTokensAtCapPasses() {
        AtomicInteger terminalCalls = new AtomicInteger();
        new FilterChain(List.of(filter), (c, s) -> terminalCalls.incrementAndGet()).next(ctx(100), new NoopSink());
        assertThat(terminalCalls).hasValue(1);
    }

    static final class NoopSink implements StreamSink {
        @Override
        public void onToken(String text) {
        }

        @Override
        public void onComplete(dev.raggateway.core.Completion completion) {
        }

        @Override
        public void onError(Throwable error) {
        }
    }
}
