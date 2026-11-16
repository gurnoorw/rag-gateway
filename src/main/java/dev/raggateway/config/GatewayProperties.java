package dev.raggateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Model catalog and provider endpoints. Model ids and prices are configuration, never code
 * (DECISIONS.md D-08); their source and date are in the comment above them in application.yml.
 *
 * @param defaultMaxTokens applied when the request has neither {@code max_tokens} nor {@code max_completion_tokens}
 * @param maxTokensCap     larger values are rejected with 400
 * @param models           public model name to provider and price
 * @param providers        provider name to endpoint and timeouts
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue("1024") int defaultMaxTokens,
        @DefaultValue("8192") int maxTokensCap,
        Map<String, ModelProperties> models,
        Map<String, ProviderProperties> providers) {

    public GatewayProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    /**
     * @param provider          provider name (key of {@code providers})
     * @param upstreamId        model id sent to the provider (equal to the public name for Claude)
     * @param inputUsdPerMtok   list price per million input tokens
     * @param outputUsdPerMtok  list price per million output tokens
     */
    public record ModelProperties(String provider, String upstreamId, BigDecimal inputUsdPerMtok,
                                  BigDecimal outputUsdPerMtok) {
    }

    /**
     * @param baseUrl           e.g. {@code https://api.anthropic.com}; tests point it at WireMock
     * @param apiKey            secret from the environment; masked in {@link #toString()}
     * @param connectTimeout    TCP/TLS connect timeout (default 1 s, t03)
     * @param firstTokenTimeout time to the first content event (default 5 s, t03, D-06)
     * @param idleTimeout       longest gap between stream events once started
     */
    public record ProviderProperties(String baseUrl, String apiKey,
                                     @DefaultValue("1s") Duration connectTimeout,
                                     @DefaultValue("5s") Duration firstTokenTimeout,
                                     @DefaultValue("30s") Duration idleTimeout) {
        @Override
        public String toString() {
            return "ProviderProperties[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<set>")
                    + ", connectTimeout=" + connectTimeout + ", firstTokenTimeout=" + firstTokenTimeout
                    + ", idleTimeout=" + idleTimeout + "]";
        }
    }
}
