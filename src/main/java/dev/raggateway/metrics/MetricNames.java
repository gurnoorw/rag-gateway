package dev.raggateway.metrics;

import java.util.Set;

/**
 * Every custom metric name, in Micrometer dotted form (Prometheus adds {@code _total}, {@code _seconds}).
 * Fixed here so each task records its own metrics without waiting for t04, and the t04 dashboard can be
 * written against stable names. Only labels in {@link #ALLOWED_LABELS} may be used (G13, AC-t04-3).
 */
public final class MetricNames {

    // t01 / t04: requests
    /** Counter. Tags: tenant, provider, model, status_class (2xx/4xx/5xx), code. */
    public static final String REQUESTS = "gateway.requests";
    /** Timer with histogram. Tags: provider, model. Total request time. */
    public static final String REQUEST_DURATION = "gateway.request.duration";
    /** Timer with histogram. Tags: provider, model. Time to first token sent to the client. */
    public static final String TIME_TO_FIRST_TOKEN = "gateway.request.ttft";
    /** Timer with histogram. Tags: provider. Time spent in the gateway excluding provider time. */
    public static final String GATEWAY_OVERHEAD = "gateway.overhead";
    /** Counter. Tags: tenant, model, direction (in/out). */
    public static final String TOKENS = "gateway.tokens";
    /** Counter, USD. Tags: tenant. */
    public static final String COST_USD = "gateway.cost.usd";
    /** Timer. Request-log insert time (C7, t08 scenario 8). */
    public static final String REQUEST_LOG_WRITE = "gateway.requestlog.write";
    /** Counter. Failed request-log inserts (C7). */
    public static final String REQUEST_LOG_WRITE_FAILURES = "gateway.requestlog.write.failures";
    /** Counter. Tags: listener. Completion listeners that threw. */
    public static final String LISTENER_FAILURES = "gateway.listener.failures";

    // t02
    /** Counter. Tags: tenant, reason (rate_limited/budget_exceeded/too_large). */
    public static final String LIMIT_REJECTIONS = "gateway.ratelimit.rejections";
    /** Counter. Tags: component (limiter/budget). Redis unavailable, local fallback used. */
    public static final String LIMIT_FAIL_OPEN = "gateway.ratelimit.failopen";

    // t03 (breaker state comes from resilience4j-micrometer: resilience4j.circuitbreaker.state, tag name)
    /** Counter. Tags: provider. */
    public static final String PROVIDER_RETRIES = "gateway.provider.retries";
    /** Counter. Tags: from_provider, to_provider. */
    public static final String PROVIDER_FALLBACKS = "gateway.provider.fallbacks";

    // t05
    /** Counter. Tags: result (hit/miss/bypass/not_eligible). */
    public static final String CACHE_LOOKUPS = "gateway.cache.lookups";
    /** Counter. Tags: operation (get/put/lock/unlock). Redis errors swallowed by the cache. */
    public static final String CACHE_ERRORS = "gateway.cache.errors";

    // t06
    /** Counter. Tags: rule, model. */
    public static final String ROUTING_DECISIONS = "gateway.routing.decisions";

    // t07
    /** Counter. Tags: model, kind (schema/structural). */
    public static final String VALIDATION_FAILURES = "gateway.validation.failures";

    // t09-t12
    /** Counter. Tags: result (queued/unchanged/indexed/stale_skipped/failed/dead/deleted). */
    public static final String INGEST_DOCUMENTS = "rag.ingest.documents";
    /** Gauge. Outbox jobs currently dead. */
    public static final String OUTBOX_DEAD = "rag.outbox.dead";
    /** Timer. Tags: mode (hybrid/vector/keyword). */
    public static final String RETRIEVAL_DURATION = "rag.retrieval.duration";

    // Fixed label values (t04, D-38): a label value must come from a bounded set, never from the request.
    /** {@code provider} label when no provider was called (rejected before the terminal). */
    public static final String PROVIDER_NONE = "none";
    /** {@code provider} label for a request answered from the response cache (t05). */
    public static final String PROVIDER_CACHE = "cache";
    /** {@code model} label for a model name that is not in the catalog (e.g. a 400 for an unknown model). */
    public static final String MODEL_OTHER = "other";
    /** {@code code} label for a request without an error. */
    public static final String CODE_NONE = "none";

    /** Labels allowed on {@code gateway.*} and {@code rag.*} metrics. Never request ids, keys or free text. */
    public static final Set<String> ALLOWED_LABELS = Set.of(
            "tenant", "provider", "model", "status_class", "code", "direction", "reason", "component",
            "listener", "from_provider", "to_provider", "result", "operation", "rule", "kind", "mode");

    private MetricNames() {
    }
}
