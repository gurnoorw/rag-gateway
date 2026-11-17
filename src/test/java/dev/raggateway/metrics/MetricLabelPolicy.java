package dev.raggateway.metrics;

import dev.raggateway.error.ErrorCode;
import dev.raggateway.support.PrometheusScrape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The label policy of AC-t04-3 and D-38, applied to a scrape: every {@code gateway_*} and {@code rag_*} series may
 * use only the label names in {@link MetricNames#ALLOWED_LABELS} plus Prometheus' {@code le}, and every value must
 * come from that label's bounded set. On top of that, no value may contain anything the test knows came from a
 * request (request ids, API keys, prompt text, a raw model string). Resilience4j's own series ({@code name},
 * {@code state}, {@code kind}) and the JVM/HTTP series are outside the scope (D-38).
 *
 * <p>Returns violations rather than asserting, so {@code MetricLabelPolicyTest} can show that each rule fires.
 */
public final class MetricLabelPolicy {

    /** Prometheus adds this one to histogram buckets. Client-side percentiles ({@code quantile}) are not allowed. */
    static final String PROMETHEUS_LE = "le";

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?|\\+Inf");
    private static final Pattern JAVA_SIMPLE_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");
    /** Fallback for allowed labels without a dedicated set yet (t05+ add them): a short lower-case identifier. */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    private final Map<String, Predicate<String>> valueRules;
    private final Set<String> forbiddenFragments;

    /**
     * @param catalogModels       {@code gateway.models} names
     * @param providers           {@code gateway.providers} names
     * @param tenants             tenant ids that exist (the test's own)
     * @param forbiddenFragments  strings that came from requests and must appear in no label value
     */
    public MetricLabelPolicy(Set<String> catalogModels, Set<String> providers, Set<String> tenants,
                      Set<String> forbiddenFragments) {
        Set<String> providerValues = union(providers, Set.of(MetricNames.PROVIDER_CACHE, MetricNames.PROVIDER_NONE));
        Set<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).collect(Collectors.toSet());
        this.valueRules = Map.ofEntries(
                Map.entry("tenant", Set.copyOf(tenants)::contains),
                Map.entry("provider", providerValues::contains),
                Map.entry("from_provider", Set.copyOf(providers)::contains),
                Map.entry("to_provider", Set.copyOf(providers)::contains),
                Map.entry("model", union(catalogModels, Set.of(MetricNames.MODEL_OTHER))::contains),
                Map.entry("status_class", Set.of("2xx", "4xx", "5xx")::contains),
                Map.entry("code", union(codes, Set.of(MetricNames.CODE_NONE))::contains),
                Map.entry("direction", Set.of(RequestMetricsListener.IN, RequestMetricsListener.OUT)::contains),
                Map.entry("component", Set.of("limiter", "budget", "cache")::contains),
                Map.entry("reason", Set.of("rate_limited", "budget_exceeded", "too_large")::contains),
                Map.entry("mode", Set.of("hybrid", "vector", "keyword")::contains),
                Map.entry("leg", Set.of("keyword", "vector", "embed")::contains),
                Map.entry("listener", v -> JAVA_SIMPLE_NAME.matcher(v).matches()),
                Map.entry(PROMETHEUS_LE, v -> NUMBER.matcher(v).matches()));
        this.forbiddenFragments = Set.copyOf(forbiddenFragments);
    }

    public static boolean inScope(String seriesName) {
        return seriesName.startsWith("gateway_") || seriesName.startsWith("rag_");
    }

    public List<String> violations(PrometheusScrape scrape) {
        List<String> violations = new ArrayList<>();
        for (PrometheusScrape.Sample s : scrape.samples()) {
            if (!inScope(s.name())) continue;
            s.labels().forEach((label, value) -> {
                if (!MetricNames.ALLOWED_LABELS.contains(label) && !PROMETHEUS_LE.equals(label)) {
                    violations.add(s.name() + ": label '" + label + "' is not allowed");
                    return;
                }
                Predicate<String> rule = valueRules.getOrDefault(label, v -> IDENTIFIER.matcher(v).matches());
                if (!rule.test(value)) {
                    violations.add(s.name() + ": " + label + "=\"" + value + "\" is not in the bounded set");
                }
                for (String fragment : forbiddenFragments) {
                    if (value.contains(fragment)) {
                        violations.add(s.name() + ": " + label + " carries request data '" + fragment + "'");
                    }
                }
            });
        }
        return violations;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
