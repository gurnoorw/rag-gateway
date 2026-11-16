package dev.raggateway.pipeline;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.RequestContext;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.validation.OutputValidator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * First filter (DECISIONS.md C3): request checks that need configuration, so they are not part of the
 * syntactic body mapping at the edge. Each failure is a 400 before the cache, the limiter or any provider call:
 * <ul>
 *   <li>{@code max_tokens} above {@code gateway.max-tokens-cap} (t01);</li>
 *   <li>a {@code response_format} JSON schema that is not a valid schema (t07, AC-t07-6, D-43): checked here
 *       rather than at the first validation, so a caller's broken schema costs no provider call and no tokens.</li>
 * </ul>
 */
@Component
@Order(FilterOrder.REQUEST_VALIDATION)
public class RequestValidationFilter implements GatewayFilter {

    private final GatewayProperties properties;
    private final OutputValidator outputValidator;

    public RequestValidationFilter(GatewayProperties properties, OutputValidator outputValidator) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.outputValidator = Objects.requireNonNull(outputValidator, "outputValidator");
    }

    @Override
    public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException {
        int maxTokens = ctx.request().maxTokens();
        if (maxTokens > properties.maxTokensCap()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "'max_tokens' " + maxTokens + " exceeds the limit of " + properties.maxTokensCap());
        }
        if (ctx.request().hasSchema()) outputValidator.checkSchema(ctx.request().jsonSchema());
        chain.next(ctx, sink);
    }
}
