package dev.raggateway.core;

import java.math.BigDecimal;
import java.util.Set;

/**
 * A team that owns API keys, limits and spend. Unlike the LLD record, ACL groups are not here: they
 * belong to the API key (DECISIONS.md C16), see {@link ApiKeyPrincipal}.
 *
 * @param id               stable id, used in Redis keys, log rows and metric labels
 * @param name             display name
 * @param tokensPerMinute  token-bucket capacity and refill per minute (t02)
 * @param monthlyBudgetUsd spend cap per calendar month, UTC (t02)
 * @param allowedModels    public model names this tenant may use; empty means every catalog model (t06)
 */
public record Tenant(String id, String name, long tokensPerMinute, BigDecimal monthlyBudgetUsd,
                     Set<String> allowedModels) {
    public Tenant {
        allowedModels = Set.copyOf(allowedModels);
    }

    public boolean allows(String model) {
        return allowedModels.isEmpty() || allowedModels.contains(model);
    }
}
