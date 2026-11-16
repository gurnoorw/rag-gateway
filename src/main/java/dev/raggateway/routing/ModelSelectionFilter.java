package dev.raggateway.routing;

import dev.raggateway.core.RequestContext;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.FilterChain;
import dev.raggateway.pipeline.FilterOrder;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.StreamSink;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves the model before the cache lookup (DECISIONS.md C3), so later steps and the cache key see
 * the resolved model (AC-t06-8): the rate-limit reservation, the router's primary and pricing all read
 * {@code ctx.request().model()} after this filter. Writes the resolved model into the context's request and
 * the rule name ({@code default} when no rule matched, null for an explicit model) into {@code routeRule},
 * which the edge returns as {@code X-Gateway-Route-Rule} and the request log stores as {@code route_rule}.
 * A rejection (400 unknown model, 403 {@code model_not_allowed}) ends the request before any provider call.
 */
@Component
@Order(FilterOrder.MODEL_SELECTION)
public class ModelSelectionFilter implements GatewayFilter {

    private final ModelSelector selector;
    private final TokenEstimator estimator;

    public ModelSelectionFilter(ModelSelector selector, TokenEstimator estimator) {
        this.selector = selector;
        this.estimator = estimator;
    }

    @Override
    public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException {
        ModelSelection selection = selector.select(ctx.request(), ctx.tenant(), estimator.estimateInput(ctx.request()));
        if (!selection.model().equals(ctx.request().model())) {
            ctx.setRequest(ctx.request().withModel(selection.model()));
        }
        ctx.setRouteRule(selection.rule());
        chain.next(ctx, sink);
    }
}
