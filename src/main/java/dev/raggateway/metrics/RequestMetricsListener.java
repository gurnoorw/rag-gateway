package dev.raggateway.metrics;

import dev.raggateway.core.Attempt;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.RequestContext;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.pipeline.RequestCompletionListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Request-level metrics (t04, D-19, D-38), recorded once per request from the finished {@link RequestContext}
 * after the response was written and closed, so none of this is on the client's latency path (C7):
 *
 * <ul>
 *   <li>{@code gateway.requests{tenant,provider,model,status_class,code}}: one per request that reached the pipeline.</li>
 *   <li>{@code gateway.request.duration{provider,model}}: start to {@link RequestContext#finishNanos()} (D-31), the
 *       same end point as the request-log row's {@code latency_ms}.</li>
 *   <li>{@code gateway.request.ttft{provider,model}}: start to the first token handed to the client sink; only
 *       recorded when a token was sent. For {@code stream:false} that is when the first token reached the JSON
 *       sink's buffer, not when the client received a byte (the body is sent at the end).</li>
 *   <li>{@code gateway.overhead{provider}}: total minus the sum of every attempt's duration (retries and fallbacks
 *       included) minus deliberate waits ({@link RequestContext#waitNanos()}: retry backoff, later the cache fill
 *       wait), i.e. time spent in the gateway's own work: auth, parsing, filters, Redis and database calls on the
 *       path, error rendering, closing the response (D-39). Writing tokens to the client happens inside an
 *       attempt and is not overhead. A cache hit or a rejected request has no attempt, so its overhead is its
 *       total time minus waits.</li>
 *   <li>{@code gateway.tokens{tenant,model,direction}}: each attempt's usage under that attempt's model, so the
 *       counters sum to {@link RequestContext#billedUsage()} (the log row and the budget).</li>
 *   <li>{@code gateway.cost.usd{tenant}}: {@link CostCalculator#costMicroUsd} of all attempts, the amount the log
 *       row stores and the budget was charged.</li>
 * </ul>
 *
 * <p>Label values come only from bounded sets (D-38): {@code provider} is the serving attempt's provider (a name
 * from {@code gateway.providers}), {@code cache} for a cache hit or {@code none} when no provider was called;
 * {@code model} is a catalog name or {@code other}, never the string the client sent; {@code status_class} is
 * {@code 2xx/4xx/5xx}; {@code code} is an {@code ErrorCode} or {@code none}. Histograms carry no {@code tenant}.
 * Bucket boundaries are configured in {@link MetricsConfig}, not here, so every timer of a name has the same ones.
 *
 * <p>Requests rejected at the HTTP edge before the pipeline (missing or invalid key, unreadable body) are not
 * counted here: no {@link RequestContext} exists for them.
 */
public final class RequestMetricsListener implements RequestCompletionListener {

    static final String IN = "in";
    static final String OUT = "out";

    private final MeterRegistry registry;
    private final CostCalculator costs;
    private final Set<String> catalogModels;
    private final LongSupplier nanoClock;

    /**
     * @param catalogModels public model names of {@code gateway.models}; any other model becomes {@code other}
     */
    public RequestMetricsListener(MeterRegistry registry, CostCalculator costs, Set<String> catalogModels) {
        this(registry, costs, catalogModels, System::nanoTime);
    }

    RequestMetricsListener(MeterRegistry registry, CostCalculator costs, Set<String> catalogModels,
                           LongSupplier nanoClock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.catalogModels = Set.copyOf(catalogModels);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public void onRequestFinished(RequestContext ctx) {
        // The pipeline always finishes the context before notifying listeners; the clock is only a guard.
        long end = ctx.finishNanos() >= 0 ? ctx.finishNanos() : nanoClock.getAsLong();
        String tenant = ctx.tenant().id();
        Attempt serving = ctx.servingAttempt().orElse(null);
        String provider = providerLabel(ctx, serving);
        String model = modelLabel(serving != null ? serving.model() : ctx.request().model());

        Counter.builder(MetricNames.REQUESTS)
                .description("Requests that reached the pipeline")
                .tags("tenant", tenant, "provider", provider, "model", model,
                        "status_class", statusClass(ctx.httpStatus()),
                        "code", ctx.errorCode() == null ? MetricNames.CODE_NONE : ctx.errorCode().code())
                .register(registry).increment();

        long total = Math.max(0, end - ctx.startNanos());
        Timer.builder(MetricNames.REQUEST_DURATION)
                .description("Request time from receipt to the response being closed (D-31)")
                .tags("provider", provider, "model", model)
                .register(registry).record(total, TimeUnit.NANOSECONDS);

        if (ctx.firstTokenSent()) {
            Timer.builder(MetricNames.TIME_TO_FIRST_TOKEN)
                    .description("Time from receipt to the first token handed to the client")
                    .tags("provider", provider, "model", model)
                    .register(registry).record(Math.max(0, ctx.firstTokenNanos() - ctx.startNanos()), TimeUnit.NANOSECONDS);
        }

        long providerNanos = 0;
        for (Attempt a : ctx.attempts()) providerNanos += Math.max(0, a.durationNanos());
        Timer.builder(MetricNames.GATEWAY_OVERHEAD)
                .description("Request time spent in the gateway itself: total minus provider attempts and deliberate waits (D-39)")
                .tags("provider", provider)
                .register(registry).record(Math.max(0, total - providerNanos - ctx.waitNanos()), TimeUnit.NANOSECONDS);

        for (Attempt a : ctx.attempts()) {
            String attemptModel = modelLabel(a.model());
            addTokens(tenant, attemptModel, IN, a.usage().inputTokens());
            addTokens(tenant, attemptModel, OUT, a.usage().outputTokens());
        }

        long costMicros = costs.costMicroUsd(ctx.attempts());
        if (costMicros > 0) {
            Counter.builder(MetricNames.COST_USD)
                    .description("Cost of provider attempts in USD, as billed to the tenant's budget")
                    .tags("tenant", tenant)
                    .register(registry).increment(costMicros / 1_000_000.0);
        }
    }

    private void addTokens(String tenant, String model, String direction, int tokens) {
        if (tokens <= 0) return;
        Counter.builder(MetricNames.TOKENS)
                .description("Tokens billed per attempt, under the attempt's model")
                .tags("tenant", tenant, "model", model, "direction", direction)
                .register(registry).increment(tokens);
    }

    private static String providerLabel(RequestContext ctx, Attempt serving) {
        if (serving != null) return serving.provider();
        return ctx.cacheStatus() == CacheStatus.HIT ? MetricNames.PROVIDER_CACHE : MetricNames.PROVIDER_NONE;
    }

    /** A catalog model name, else {@code other}: the raw string is client-controlled and unbounded. */
    String modelLabel(String model) {
        return model != null && catalogModels.contains(model) ? model : MetricNames.MODEL_OTHER;
    }

    /** {@code 2xx}, {@code 4xx} (including 499, client closed) or {@code 5xx}. */
    static String statusClass(int httpStatus) {
        if (httpStatus >= 500) return "5xx";
        if (httpStatus >= 400) return "4xx";
        if (httpStatus >= 200) return "2xx";
        return "5xx"; // not finished: cannot happen after the pipeline ran; counted as a server fault, not hidden
    }
}
