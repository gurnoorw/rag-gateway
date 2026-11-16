package dev.raggateway.cost;

import dev.raggateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * List prices per public model name, read from {@code gateway.models} (DECISIONS.md D-08: prices are
 * configuration, never code). Built once at startup; a catalog model without both prices, or with a negative
 * one, fails startup, so a running gateway never prices a request with a missing number. Immutable.
 *
 * <p>One tier per model: Haiku prompts above 100K tokens (priced higher upstream) are under-costed (D-08).
 */
@Component
public class PriceTable {

    /**
     * @param inputUsdPerMtok  USD per million input tokens
     * @param outputUsdPerMtok USD per million output tokens
     */
    public record Price(BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok) {
        public Price {
            Objects.requireNonNull(inputUsdPerMtok, "inputUsdPerMtok");
            Objects.requireNonNull(outputUsdPerMtok, "outputUsdPerMtok");
            if (inputUsdPerMtok.signum() < 0 || outputUsdPerMtok.signum() < 0) {
                throw new IllegalArgumentException("prices must be >= 0");
            }
        }
    }

    private final Map<String, Price> prices;

    @Autowired
    public PriceTable(GatewayProperties properties) {
        this(fromCatalog(properties.models()));
    }

    /** An explicit table (tests). */
    public PriceTable(Map<String, Price> prices) {
        this.prices = Map.copyOf(prices);
    }

    private static Map<String, Price> fromCatalog(Map<String, GatewayProperties.ModelProperties> models) {
        Map<String, Price> table = new HashMap<>();
        models.forEach((name, m) -> {
            if (m.inputUsdPerMtok() == null || m.outputUsdPerMtok() == null) {
                throw new IllegalStateException("gateway.models." + name
                        + " needs input-usd-per-mtok and output-usd-per-mtok (0 for a free model)");
            }
            if (m.inputUsdPerMtok().signum() < 0 || m.outputUsdPerMtok().signum() < 0) {
                throw new IllegalStateException("gateway.models." + name + " has a negative price");
            }
            table.put(name, new Price(m.inputUsdPerMtok(), m.outputUsdPerMtok()));
        });
        return table;
    }

    /** Price of a public model name, or empty when the model is not in the catalog. */
    public Optional<Price> price(String model) {
        return Optional.ofNullable(prices.get(model));
    }
}
