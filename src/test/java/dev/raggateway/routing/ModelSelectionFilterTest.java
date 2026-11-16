package dev.raggateway.routing;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.FilterChain;
import dev.raggateway.pipeline.StreamSink;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ExplicitModelSelector rules and how ModelSelectionFilter writes the result into the context. */
class ModelSelectionFilterTest {

    private static final GatewayProperties PROPS = new GatewayProperties(1024, 8192,
            Map.of("claude-haiku-5-5", new ModelProperties("claude", "claude-haiku-5-5", BigDecimal.ONE, BigDecimal.ONE)),
            Map.of());

    private static final TokenEstimator ESTIMATOR = new TokenEstimator() {
        @Override
        public int estimateInput(ChatRequest request) {
            return 1;
        }

        @Override
        public int estimate(String text) {
            return 1;
        }
    };

    private final ExplicitModelSelector selector = new ExplicitModelSelector(PROPS);

    static RequestContext ctx(String model) {
        var tenant = new Tenant("t1", "Tenant 1", 10_000, BigDecimal.TEN, Set.of());
        var caller = new ApiKeyPrincipal("k1", tenant, Set.of(), false);
        var request = new ChatRequest(model, List.of(new Message(Role.USER, "q")), 10,
                null, null, List.of(), false, false, null);
        return new RequestContext("r1", System.nanoTime(), caller, request);
    }

    @Test
    void catalogModelIsSelectedWithoutRule() {
        assertThat(selector.select(ctx("claude-haiku-5-5").request(), null, 0))
                .isEqualTo(new ModelSelection("claude-haiku-5-5", null));
    }

    @Test
    void autoIsRejectedWithMessage() {
        assertThatThrownBy(() -> selector.select(ctx("auto").request(), null, 0))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessage("automatic routing is not enabled");
    }

    @Test
    void unknownModelIsRejected() {
        assertThatThrownBy(() -> selector.select(ctx("gpt-4o").request(), null, 0))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessageContaining("unknown model");
    }

    @Test
    void filterAppliesSelectionAndRuleToContext() {
        ModelSelector routing = (request, tenant, tokens) -> new ModelSelection("claude-haiku-5-5", "short-prompt");
        var filter = new ModelSelectionFilter(routing, ESTIMATOR);
        var ctx = ctx("auto");
        List<String> seen = new ArrayList<>();

        new FilterChain(List.of(filter), (c, s) -> seen.add(c.request().model())).next(ctx, noop());

        assertThat(seen).containsExactly("claude-haiku-5-5");
        assertThat(ctx.requestedModel()).isEqualTo("auto");
        assertThat(ctx.routeRule()).isEqualTo("short-prompt");
    }

    @Test
    void filterRejectsBeforeTerminal() {
        var filter = new ModelSelectionFilter(selector, ESTIMATOR);
        List<String> seen = new ArrayList<>();

        assertThatThrownBy(() -> new FilterChain(List.of(filter), (c, s) -> seen.add("terminal")).next(ctx("auto"), noop()))
                .isInstanceOf(GatewayException.class);
        assertThat(seen).isEmpty();
    }

    private static StreamSink noop() {
        return new StreamSink() {
            @Override
            public void onToken(String text) {
            }

            @Override
            public void onComplete(dev.raggateway.core.Completion completion) {
            }

            @Override
            public void onError(Throwable error) {
            }
        };
    }
}
