package dev.raggateway.validation;

import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.Set;

/**
 * {@code gateway.validation.failures{model, kind}} (D-19, D-38): {@code kind} is {@link #SCHEMA} (output rejected by
 * {@link ValidatingTerminal}: schema errors, truncated or refused) or {@link #STRUCTURAL} (an empty {@code stop}
 * answer rejected by {@link StructuralCheckingProvider}). {@code model} is a catalog name or
 * {@link MetricNames#MODEL_OTHER}, never a string from the request, so both labels stay bounded.
 */
public final class ValidationFailures {

    public static final String SCHEMA = "schema";
    public static final String STRUCTURAL = "structural";

    private final MeterRegistry registry;
    private final Set<String> catalogModels;

    /** @param catalogModels {@code gateway.models} names */
    public ValidationFailures(MeterRegistry registry, Set<String> catalogModels) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalogModels = Set.copyOf(catalogModels);
    }

    public void increment(String model, String kind) {
        Counter.builder(MetricNames.VALIDATION_FAILURES)
                .description("Model answers rejected by output validation, per attempt")
                .tag("model", catalogModels.contains(model) ? model : MetricNames.MODEL_OTHER)
                .tag("kind", kind)
                .register(registry)
                .increment();
    }
}
