package dev.raggateway.cost;

import dev.raggateway.core.Attempt;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Usage;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.metrics.MetricsConfig;
import dev.raggateway.pipeline.RequestCompletionListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Writes one {@code request_log} row per request that reached the pipeline (DECISIONS.md C7): a single
 * {@code INSERT} on the request's own thread, called after the response has been written and closed, so
 * it is off the client's latency path. No prompt or response text is written (G7, AC-t01-7).
 *
 * <p>Failure behaviour: the insert never fails the request. A failed insert increments
 * {@code gateway.requestlog.write.failures} and writes one WARN line holding everything the row would have
 * held except text, so the row can be rebuilt from logs. The insert time is the
 * {@code gateway.requestlog.write} timer.
 *
 * <p>{@code cost_usd} is {@link CostCalculator#costUsd} over every attempt: the same micro-USD amount the
 * rate-limit filter added to the tenant's monthly budget (DECISIONS.md D-33). A request with no attempt
 * (rejected by a filter, cache hit) costs 0.
 */
public final class RequestLogWriter implements RequestCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(RequestLogWriter.class);

    /** Provider name shown for a request answered from the cache (same value as the response header). */
    static final String CACHE_PROVIDER = "cache";

    private static final String INSERT = """
            INSERT INTO request_log (request_id, tenant_id, api_key_id, requested_model, model_used, provider_used,
                                     route_rule, fallback_used, attempts, input_tokens, output_tokens, usage_estimated,
                                     cost_usd, latency_ms, ttft_ms, stream, cache_status, status, error_code, http_status)
            VALUES (:requestId, :tenantId, :apiKeyId, :requestedModel, :modelUsed, :providerUsed,
                    :routeRule, :fallbackUsed, :attempts, :inputTokens, :outputTokens, :usageEstimated,
                    :costUsd, :latencyMs, :ttftMs, :stream, :cacheStatus, :status, :errorCode, :httpStatus)
            """;

    private final JdbcClient jdbc;
    private final CostCalculator costs;
    private final Timer writeTimer;
    private final Counter writeFailures;
    private final LongSupplier nanoClock;

    public RequestLogWriter(JdbcClient jdbc, MeterRegistry registry, CostCalculator costs) {
        this(jdbc, registry, costs, System::nanoTime);
    }

    RequestLogWriter(JdbcClient jdbc, MeterRegistry registry, CostCalculator costs, LongSupplier nanoClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.writeTimer = Timer.builder(MetricNames.REQUEST_LOG_WRITE)
                .description("Request-log insert time")
                .register(registry);
        this.writeFailures = Counter.builder(MetricNames.REQUEST_LOG_WRITE_FAILURES)
                .description(MetricsConfig.REQUEST_LOG_WRITE_FAILURES_DESCRIPTION)
                .register(registry);
    }

    @Override
    public void onRequestFinished(RequestContext ctx) {
        Row row;
        try {
            // Latency ends when the pipeline finished the request, not when this listener runs (D-31).
            row = Row.of(ctx, ctx.finishNanos() >= 0 ? ctx.finishNanos() : nanoClock.getAsLong(),
                    costs.costUsd(ctx.attempts()));
        } catch (RuntimeException e) {
            writeFailures.increment();
            log.warn("request log row not built for request {}: {}", ctx.requestId(), e.toString());
            return;
        }
        long start = System.nanoTime();
        try {
            insert(row);
        } catch (RuntimeException e) {
            writeFailures.increment();
            log.warn("request log write failed: {} ({})", row.describe(), e.getClass().getSimpleName());
        } finally {
            writeTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private void insert(Row r) {
        jdbc.sql(INSERT)
                .param("requestId", r.requestId)
                .param("tenantId", r.tenantId)
                .param("apiKeyId", r.apiKeyId)
                .param("requestedModel", r.requestedModel)
                .param("modelUsed", r.modelUsed)
                .param("providerUsed", r.providerUsed)
                .param("routeRule", r.routeRule)
                .param("fallbackUsed", r.fallbackUsed)
                .param("attempts", r.attempts)
                .param("inputTokens", r.usage.inputTokens())
                .param("outputTokens", r.usage.outputTokens())
                .param("usageEstimated", r.usageEstimated)
                .param("costUsd", r.costUsd)
                .param("latencyMs", r.latencyMs)
                .param("ttftMs", r.ttftMs)
                .param("stream", r.stream)
                .param("cacheStatus", r.cacheStatus)
                .param("status", r.status)
                .param("errorCode", r.errorCode)
                .param("httpStatus", r.httpStatus)
                .update();
    }

    /** Column values of one row, computed once so the WARN fallback line shows exactly what was lost. */
    record Row(UUID requestId, String tenantId, UUID apiKeyId, String requestedModel, String modelUsed,
               String providerUsed, String routeRule, boolean fallbackUsed, int attempts, Usage usage,
               boolean usageEstimated, BigDecimal costUsd, int latencyMs, Integer ttftMs, boolean stream,
               String cacheStatus, String status, String errorCode, int httpStatus) {

        static Row of(RequestContext ctx, long nowNanos, BigDecimal costUsd) {
            Attempt serving = ctx.servingAttempt().orElse(null);
            boolean hit = ctx.cacheStatus() == CacheStatus.HIT;
            String modelUsed = serving != null ? serving.model() : hit ? ctx.request().model() : null;
            String providerUsed = serving != null ? serving.provider() : hit ? CACHE_PROVIDER : null;
            boolean estimated = ctx.attempts().stream().anyMatch(Attempt::usageEstimated);
            int latencyMs = millis(nowNanos - ctx.startNanos());
            Integer ttftMs = ctx.firstTokenSent() ? millis(ctx.firstTokenNanos() - ctx.startNanos()) : null;
            String status = ctx.outcome() == null ? "error" : ctx.outcome().logValue();
            return new Row(UUID.fromString(ctx.requestId()), ctx.tenant().id(), uuidOrNull(ctx.caller().keyId()),
                    ctx.requestedModel(), modelUsed, providerUsed, ctx.routeRule(), ctx.fallbackUsed(),
                    ctx.attempts().size(), ctx.billedUsage(), estimated, costUsd, latencyMs, ttftMs,
                    ctx.request().stream(), ctx.cacheStatus().name().toLowerCase(Locale.ROOT), status,
                    ctx.errorCode() == null ? null : ctx.errorCode().code(), ctx.httpStatus());
        }

        /** One line for the WARN fallback: ids, model, provider, tokens, cost and status; never text. */
        String describe() {
            return "request_id=" + requestId + " tenant=" + tenantId + " api_key_id=" + apiKeyId
                    + " requested_model=" + requestedModel + " model_used=" + modelUsed
                    + " provider=" + providerUsed + " route_rule=" + routeRule + " fallback=" + fallbackUsed
                    + " attempts=" + attempts + " input_tokens=" + usage.inputTokens()
                    + " output_tokens=" + usage.outputTokens() + " usage_estimated=" + usageEstimated
                    + " cost_usd=" + costUsd.toPlainString() + " latency_ms=" + latencyMs + " ttft_ms=" + ttftMs
                    + " stream=" + stream + " cache=" + cacheStatus + " status=" + status
                    + " error_code=" + errorCode + " http_status=" + httpStatus;
        }

        private static int millis(long nanos) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, nanos / 1_000_000));
        }

        private static UUID uuidOrNull(String id) {
            try {
                return id == null ? null : UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
