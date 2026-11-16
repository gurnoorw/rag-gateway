package dev.raggateway.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Model routing for {@code "model":"auto"} ({@code gateway.routing}, t06, DECISIONS.md D-42). Ordered rules, first
 * match wins; no match means {@link #defaultModel()}. Defaults live here (one rule {@code short} to the cheap tier,
 * the strong tier otherwise, D-08); application.yml only overrides. Whether each model is in the catalog is checked
 * by {@link RuleBasedModelSelector} at startup, which has the catalog.
 *
 * <p>Rule names become a metric label value and the {@code X-Gateway-Route-Rule} header, so they are restricted to
 * short lower-case identifiers ({@value #NAME_PATTERN}) and may not be {@value #DEFAULT_RULE} or
 * {@value #EXPLICIT_RULE}, which the selector reports itself.
 *
 * @param defaultModel catalog model used when no rule matches (default {@code claude-opus-5-5})
 * @param rules        ordered rules; {@code null} gives the starting rule set, an empty list routes everything to
 *                     the default
 */
@ConfigurationProperties("gateway.routing")
public record RoutingProperties(
        @DefaultValue("claude-opus-5-5") String defaultModel,
        List<Rule> rules) {

    /** Route rule reported when no rule matched and the default model was used. */
    public static final String DEFAULT_RULE = "default";
    /** Counter label for a request that named its model (the header and log carry no rule then). */
    public static final String EXPLICIT_RULE = "explicit";
    static final String NAME_PATTERN = "[a-z][a-z0-9_]{0,39}";
    private static final Pattern NAME = Pattern.compile(NAME_PATTERN);

    /** The starting rule: short prompt, small output, no schema → the cheap tier (D-42). */
    public static final Rule SHORT = new Rule("short", "claude-haiku-5-5", new When(2_000, null, 1_024, false));

    public RoutingProperties {
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new IllegalArgumentException("gateway.routing.default-model must be set");
        }
        rules = rules == null ? List.of(SHORT) : List.copyOf(rules);
        Set<String> names = new HashSet<>();
        for (Rule rule : rules) {
            if (!names.add(rule.name())) {
                throw new IllegalArgumentException("gateway.routing.rules: duplicate rule name '" + rule.name() + "'");
            }
        }
    }

    public static RoutingProperties defaults() {
        return new RoutingProperties("claude-opus-5-5", null);
    }

    /**
     * @param name  bounded identifier, unique in the list; shown in the header, the log row and the counter
     * @param model catalog model this rule routes to
     * @param when  conditions, all of which must hold; absent means the rule always matches
     */
    public record Rule(String name, String model, When when) {
        public Rule {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("gateway.routing.rules: rule name '" + name + "' must match "
                        + NAME_PATTERN);
            }
            if (DEFAULT_RULE.equals(name) || EXPLICIT_RULE.equals(name)) {
                throw new IllegalArgumentException("gateway.routing.rules: rule name '" + name + "' is reserved");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("gateway.routing.rules: rule '" + name + "' has no model");
            }
            when = when == null ? When.ALWAYS : when;
        }

        boolean matches(int estimatedInputTokens, int maxTokens, boolean hasSchema) {
            return when.matches(estimatedInputTokens, maxTokens, hasSchema);
        }
    }

    /**
     * Optional conditions, AND-ed. A {@code null} condition is not checked.
     *
     * @param maxInputTokens  estimated input tokens must be at most this
     * @param minInputTokens  estimated input tokens must be at least this
     * @param maxOutputTokens the request's {@code max_tokens} (after the edge's default) must be at most this
     * @param hasSchema       whether a JSON schema must ({@code true}) or must not ({@code false}) be present
     */
    public record When(Integer maxInputTokens, Integer minInputTokens, Integer maxOutputTokens, Boolean hasSchema) {

        static final When ALWAYS = new When(null, null, null, null);

        public When {
            nonNegative(maxInputTokens, "max-input-tokens");
            nonNegative(minInputTokens, "min-input-tokens");
            nonNegative(maxOutputTokens, "max-output-tokens");
            if (maxInputTokens != null && minInputTokens != null && minInputTokens > maxInputTokens) {
                throw new IllegalArgumentException("gateway.routing.rules: min-input-tokens " + minInputTokens
                        + " is above max-input-tokens " + maxInputTokens + "; the rule could never match");
            }
        }

        boolean matches(int estimatedInputTokens, int maxTokens, boolean schema) {
            return (maxInputTokens == null || estimatedInputTokens <= maxInputTokens)
                    && (minInputTokens == null || estimatedInputTokens >= minInputTokens)
                    && (maxOutputTokens == null || maxTokens <= maxOutputTokens)
                    && (hasSchema == null || hasSchema == schema);
        }

        private static void nonNegative(Integer value, String name) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException("gateway.routing.rules: " + name + " must be >= 0");
            }
        }
    }
}
