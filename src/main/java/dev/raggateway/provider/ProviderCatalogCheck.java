package dev.raggateway.provider;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Startup check (t03, DECISIONS.md D-30): every model in {@code gateway.models} must name a provider that has an
 * {@link LlmProvider} bean. Otherwise the application fails to start, naming the offending models, so a running
 * gateway can never accept a catalog model that no adapter serves (in t01 that case was a 400 at request time).
 *
 * <p>Rejected: checking per request (the misconfiguration would surface as client errors, possibly only on the
 * fallback path during an outage); ignoring such models (a typo in config would silently shrink the catalog).
 */
@Component
public final class ProviderCatalogCheck {

    public ProviderCatalogCheck(GatewayProperties properties, ObjectProvider<LlmProvider> providers) {
        verify(properties.models(), providers.orderedStream().map(LlmProvider::name).collect(Collectors.toSet()));
    }

    /**
     * @param models    the catalog
     * @param providers names of the configured adapters
     * @throws IllegalStateException listing every catalog model whose provider has no adapter
     */
    static void verify(Map<String, ModelProperties> models, Collection<String> providers) {
        Map<String, String> missing = new TreeMap<>();
        models.forEach((model, m) -> {
            String provider = m == null ? null : m.provider();
            if (provider == null || !providers.contains(provider)) missing.put(model, String.valueOf(provider));
        });
        if (missing.isEmpty()) return;
        String list = missing.entrySet().stream()
                .map(e -> "'" + e.getKey() + "' (provider '" + e.getValue() + "')")
                .collect(Collectors.joining(", "));
        Set<String> configured = new TreeSet<>(providers);
        throw new IllegalStateException("gateway.models has models whose provider has no adapter: " + list
                + "; configured adapters: " + configured
                + ". Set gateway.providers.<name>.base-url for the provider, or remove the models.");
    }
}
