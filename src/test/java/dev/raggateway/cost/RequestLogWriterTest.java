package dev.raggateway.cost;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RequestLogWriterTest extends AbstractPostgresTest {

    private static final long MS = 1_000_000L;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private String tenantId;
    private UUID keyId;

    @BeforeEach
    void setUp() {
        tenantId = "log-" + UUID.randomUUID();
        TestData.insertTenant(jdbc, tenantId, 10_000, BigDecimal.TEN, Set.of());
        keyId = TestData.insertKey(jdbc, tenantId, "rgw_test-" + UUID.randomUUID(), Set.of(), false);
    }

    private RequestContext ctx(String prompt, boolean stream) {
        var tenant = new Tenant(tenantId, "T", 10_000, BigDecimal.TEN, Set.of());
        var caller = new ApiKeyPrincipal(keyId.toString(), tenant, Set.of(), false);
        var request = new ChatRequest("claude-opus-5-5", List.of(new Message(Role.USER, prompt)), 64,
                0.0, null, List.of(), stream, false, null);
        return new RequestContext(UUID.randomUUID().toString(), 1_000 * MS, caller, request);
    }

    private Map<String, Object> row(String requestId) {
        return jdbc.sql("SELECT * FROM request_log WHERE request_id = :id")
                .param("id", UUID.fromString(requestId)).query().singleRow();
    }

    @Test
    void writesOneRowForASuccessfulStream() {
        String marker = "prompt-marker-" + UUID.randomUUID();
        RequestContext ctx = ctx(marker, true);
        ctx.recordAttempt(new Attempt("claude", "claude-opus-5-5", false, AttemptOutcome.SUCCEEDED,
                new Usage(12, 34), false, 300 * MS, 80 * MS, null));
        ctx.markFirstTokenSent(1_100 * MS);
        ctx.setCacheStatus(CacheStatus.MISS);
        ctx.finish(RequestOutcome.SUCCESS, null, 200, 1_450 * MS);
        var writer = new RequestLogWriter(jdbc, registry, () -> 1_450 * MS);

        writer.onRequestFinished(ctx);

        Map<String, Object> row = row(ctx.requestId());
        assertThat(row).containsEntry("tenant_id", tenantId)
                .containsEntry("api_key_id", keyId)
                .containsEntry("requested_model", "claude-opus-5-5")
                .containsEntry("model_used", "claude-opus-5-5")
                .containsEntry("provider_used", "claude")
                .containsEntry("fallback_used", false)
                .containsEntry("input_tokens", 12)
                .containsEntry("output_tokens", 34)
                .containsEntry("usage_estimated", false)
                .containsEntry("latency_ms", 450)
                .containsEntry("ttft_ms", 100)
                .containsEntry("stream", true)
                .containsEntry("cache_status", "miss")
                .containsEntry("status", "ok")
                .containsEntry("route_rule", null)
                .containsEntry("error_code", null);
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(200);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0");
        assertThat(registry.timer(MetricNames.REQUEST_LOG_WRITE).count()).isEqualTo(1);
        assertThat(registry.counter(MetricNames.REQUEST_LOG_WRITE_FAILURES).count()).isZero();
    }

    @Test
    void noColumnHoldsThePromptText() {
        String marker = "prompt-marker-" + UUID.randomUUID();
        RequestContext ctx = ctx("please answer " + marker, false);
        ctx.recordAttempt(new Attempt("claude", "claude-opus-5-5", false, AttemptOutcome.SUCCEEDED,
                new Usage(5, 5), false, MS, MS, null));
        ctx.finish(RequestOutcome.SUCCESS, null, 200);

        new RequestLogWriter(jdbc, registry).onRequestFinished(ctx);

        Map<String, Object> row = row(ctx.requestId());
        assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains(marker));
    }

    @Test
    void sumsAttemptsAndFlagsEstimatedUsageOnCancel() {
        RequestContext ctx = ctx("hi", true);
        ctx.recordAttempt(new Attempt("claude", "claude-opus-5-5", false, AttemptOutcome.CLIENT_CANCELLED,
                new Usage(7, 3), true, MS, MS, "CLIENT_CANCELLED"));
        ctx.markFirstTokenSent(1_010 * MS);
        ctx.finish(RequestOutcome.CLIENT_CANCELLED, null, 200);

        new RequestLogWriter(jdbc, registry).onRequestFinished(ctx);

        assertThat(row(ctx.requestId())).containsEntry("status", "client_cancelled")
                .containsEntry("usage_estimated", true)
                .containsEntry("input_tokens", 7)
                .containsEntry("output_tokens", 3);
    }

    @Test
    void errorRowCarriesCodeAndStatusAndNoProvider() {
        RequestContext ctx = ctx("hi", false);
        ctx.finish(RequestOutcome.ERROR, ErrorCode.INVALID_REQUEST, 400);

        new RequestLogWriter(jdbc, registry).onRequestFinished(ctx);

        Map<String, Object> row = row(ctx.requestId());
        assertThat(row).containsEntry("status", "error")
                .containsEntry("error_code", "invalid_request")
                .containsEntry("provider_used", null)
                .containsEntry("model_used", null)
                .containsEntry("ttft_ms", null)
                .containsEntry("cache_status", "not_eligible");
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(400);
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
    }

    @Test
    void cacheHitIsLoggedWithCacheAsProvider() {
        RequestContext ctx = ctx("hi", true);
        ctx.setCacheStatus(CacheStatus.HIT);
        ctx.finish(RequestOutcome.SUCCESS, null, 200);

        new RequestLogWriter(jdbc, registry).onRequestFinished(ctx);

        assertThat(row(ctx.requestId())).containsEntry("provider_used", "cache")
                .containsEntry("model_used", "claude-opus-5-5")
                .containsEntry("cache_status", "hit");
    }

    @Test
    void failedInsertIsCountedLoggedAndNeverThrown(CapturedOutput output) throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        var writer = new RequestLogWriter(JdbcClient.create(broken), registry);
        String marker = "prompt-marker-" + UUID.randomUUID();
        RequestContext ctx = ctx(marker, true);
        ctx.recordAttempt(new Attempt("claude", "claude-opus-5-5", false, AttemptOutcome.SUCCEEDED,
                new Usage(12, 34), false, MS, MS, null));
        ctx.finish(RequestOutcome.SUCCESS, null, 200);

        assertThatCode(() -> writer.onRequestFinished(ctx)).doesNotThrowAnyException();

        assertThat(registry.counter(MetricNames.REQUEST_LOG_WRITE_FAILURES).count()).isEqualTo(1);
        assertThat(output.getOut()).contains("request log write failed")
                .contains("request_id=" + ctx.requestId())
                .contains("tenant=" + tenantId)
                .contains("provider=claude")
                .contains("input_tokens=12")
                .contains("output_tokens=34")
                .contains("status=ok")
                .doesNotContain(marker);
    }
}
