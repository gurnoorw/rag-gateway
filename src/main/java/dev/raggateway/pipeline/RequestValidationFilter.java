package dev.raggateway.pipeline;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.RequestContext;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * First filter (DECISIONS.md C3): request checks that need configuration, so they are not part of the
 * syntactic body mapping at the edge. t01: {@code max_tokens} above {@code gateway.max-tokens-cap} is
 * rejected with 400. t07 adds "the JSON schema compiles".
 */
@Component
@Order(FilterOrder.REQUEST_VALIDATION)
public class RequestValidationFilter implements GatewayFilter {

    private final GatewayProperties properties;

    public RequestValidationFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException {
        int maxTokens = ctx.request().maxTokens();
        if (maxTokens > properties.maxTokensCap()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "'max_tokens' " + maxTokens + " exceeds the limit of " + properties.maxTokensCap());
        }
        chain.next(ctx, sink);
    }
}
