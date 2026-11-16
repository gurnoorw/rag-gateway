package dev.raggateway.routing;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Tenant;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.springframework.stereotype.Component;

/**
 * t01 model selection: the caller must name a model from the catalog ({@code gateway.models}).
 * {@code auto} is rejected with 400 until the rule-based selector of t06 replaces this bean; the tenant's
 * allowed-models check (403) also arrives with t06.
 */
@Component
public class ExplicitModelSelector implements ModelSelector {

    private final GatewayProperties properties;

    public ExplicitModelSelector(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public ModelSelection select(ChatRequest request, Tenant tenant, int estimatedInputTokens) throws GatewayException {
        String model = request.model();
        if (ChatRequest.AUTO.equals(model)) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "automatic routing is not enabled");
        }
        if (!properties.models().containsKey(model)) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "unknown model '" + model + "'");
        }
        return new ModelSelection(model, null);
    }
}
