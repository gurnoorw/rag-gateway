package dev.raggateway.routing;

import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Tenant;
import dev.raggateway.error.GatewayException;

/**
 * Chooses the model before the cache lookup, so the cache key holds the resolved model (C3, AC-t06-8).
 * t01 ships an explicit-only implementation (unknown model or {@code auto} gives 400); t06 replaces it with
 * ordered rules from config and enforces the tenant's allowed models (403 {@code model_not_allowed}).
 */
public interface ModelSelector {

    ModelSelection select(ChatRequest request, Tenant tenant, int estimatedInputTokens) throws GatewayException;
}
