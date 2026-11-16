package dev.raggateway.routing;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Tenant;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.routing.RoutingProperties.Rule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Rule-based model selection (t06, DECISIONS.md D-42), replacing t01's explicit-only selector.
 *
 * <ul>
 *   <li>{@code auto}: the first rule of {@code gateway.routing.rules} that matches the request and whose model the
 *       tenant may use. A rule whose model the tenant may not use is skipped, so one rule set serves tenants with
 *       different entitlements. No such rule: {@code default-model} (route rule {@code default}) if allowed,
 *       otherwise 403 {@code model_not_allowed}.</li>
 *   <li>Explicit model: used unchanged if it is in the catalog (else 400 {@code invalid_request}) and allowed for
 *       the tenant (else 403 {@code model_not_allowed}). Route rule {@code null}.</li>
 * </ul>
 *
 * <p>Inputs are only what is known before the call: estimated input tokens, {@code max_tokens}, schema present.
 * Rejected: a classifier model choosing the tier (an extra call per request, opaque), latency-feedback routing
 * (needs live per-model latency), per-tenant rule sets (more config than needed).
 *
 * <p>Startup fails if the default or any rule names a model outside {@code gateway.models}: a typo would otherwise
 * show up as a 400 on {@code auto} traffic only. Every decision increments {@code gateway.routing.decisions{rule,
 * model}}; both labels come from config (rule names, catalog models, {@code default}, {@code explicit}), so the
 * series are bounded and registered at 0 at startup (D-38).
 */
@Component
public class RuleBasedModelSelector implements ModelSelector {

    private final GatewayProperties gateway;
    private final List<Rule> rules;
    private final String defaultModel;
    /** Keyed by rule label then model; every combination that can occur, registered up front. */
    private final Map<String, Map<String, Counter>> decisions = new HashMap<>();

    public RuleBasedModelSelector(GatewayProperties gateway, RoutingProperties routing, MeterRegistry registry) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.rules = routing.rules();
        this.defaultModel = routing.defaultModel();
        verifyCatalog(gateway, routing);
        for (Rule rule : rules) register(registry, rule.name(), rule.model());
        register(registry, RoutingProperties.DEFAULT_RULE, defaultModel);
        for (String model : gateway.models().keySet()) register(registry, RoutingProperties.EXPLICIT_RULE, model);
    }

    /**
     * @throws IllegalStateException naming every rule (and the default) whose model is not in the catalog
     */
    static void verifyCatalog(GatewayProperties gateway, RoutingProperties routing) {
        Map<String, String> missing = new LinkedHashMap<>();
        for (Rule rule : routing.rules()) {
            if (!gateway.models().containsKey(rule.model())) missing.put("rule '" + rule.name() + "'", rule.model());
        }
        if (!gateway.models().containsKey(routing.defaultModel())) {
            missing.put("default-model", routing.defaultModel());
        }
        if (missing.isEmpty()) return;
        StringBuilder list = new StringBuilder();
        missing.forEach((where, model) -> list.append(list.isEmpty() ? "" : ", ").append(where)
                .append(" -> '").append(model).append("'"));
        throw new IllegalStateException("gateway.routing names models that are not in gateway.models: " + list
                + "; catalog: " + new TreeSet<>(gateway.models().keySet()));
    }

    @Override
    public ModelSelection select(ChatRequest request, Tenant tenant, int estimatedInputTokens) throws GatewayException {
        Objects.requireNonNull(tenant, "tenant");
        String model = request.model();
        if (!ChatRequest.AUTO.equals(model)) {
            if (!gateway.models().containsKey(model)) {
                throw new GatewayException(ErrorCode.INVALID_REQUEST, "unknown model '" + model + "'");
            }
            if (!tenant.allows(model)) {
                throw new GatewayException(ErrorCode.MODEL_NOT_ALLOWED,
                        "model '" + model + "' is not allowed for this tenant");
            }
            count(RoutingProperties.EXPLICIT_RULE, model);
            return new ModelSelection(model, null);
        }
        for (Rule rule : rules) {
            if (rule.matches(estimatedInputTokens, request.maxTokens(), request.hasSchema())
                    && tenant.allows(rule.model())) {
                count(rule.name(), rule.model());
                return new ModelSelection(rule.model(), rule.name());
            }
        }
        if (tenant.allows(defaultModel)) {
            count(RoutingProperties.DEFAULT_RULE, defaultModel);
            return new ModelSelection(defaultModel, RoutingProperties.DEFAULT_RULE);
        }
        throw new GatewayException(ErrorCode.MODEL_NOT_ALLOWED,
                "no model allowed for this tenant matches the routing rules; name a model explicitly");
    }

    private void register(MeterRegistry registry, String rule, String model) {
        decisions.computeIfAbsent(rule, r -> new HashMap<>()).computeIfAbsent(model, m -> Counter
                .builder(MetricNames.ROUTING_DECISIONS)
                .description("Model selection decisions by rule (rule name, default or explicit) and chosen model")
                .tag("rule", rule)
                .tag("model", m)
                .register(registry));
    }

    private void count(String rule, String model) {
        decisions.get(rule).get(model).increment();
    }
}
