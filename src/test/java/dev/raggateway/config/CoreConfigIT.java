package dev.raggateway.config;

import dev.raggateway.auth.ApiKeyAuthenticator;
import dev.raggateway.auth.JdbcApiKeyAuthenticator;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.core.Role;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.cost.RequestLogWriter;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.pipeline.GatewayPipeline;
import dev.raggateway.pipeline.RequestCompletionListener;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.provider.claude.ClaudeProvider;
import dev.raggateway.resilience.FallbackRouter;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core beans are wired in the real application context, and the pipeline runs end to end on them:
 * key lookup, terminal (the t03 fallback router), Claude adapter (pointed at an unroutable port by the test
 * profile), request log.
 * The HTTP edge is Dev A's; the HTTP end-to-end test is ChatCompletionsE2EIT (after both branches merge).
 */
class CoreConfigIT extends AbstractIntegrationTest {

    @Autowired GatewayPipeline pipeline;
    @Autowired Terminal terminal;
    @Autowired ApiKeyAuthenticator authenticator;
    @Autowired TokenEstimator estimator;
    @Autowired List<LlmProvider> providers;
    @Autowired List<RequestCompletionListener> listeners;

    @Test
    void t01BeansAreWired() {
        assertThat(pipeline).isInstanceOf(DefaultGatewayPipeline.class);
        assertThat(terminal).isInstanceOf(FallbackRouter.class);
        assertThat(authenticator).isInstanceOf(JdbcApiKeyAuthenticator.class);
        assertThat(estimator).isNotNull();
        assertThat(providers).hasAtLeastOneElementOfType(ClaudeProvider.class);
        assertThat(listeners).hasAtLeastOneElementOfType(RequestLogWriter.class);
    }

    @Test
    void unreachableProvidersGive503AfterARetryAndOneLogRow() {
        TestData.insertTenant(jdbc, "acme", 10_000, BigDecimal.TEN, Set.of());
        String key = "rgw_test-" + UUID.randomUUID();
        TestData.insertKey(jdbc, "acme", key, Set.of(), false);
        ApiKeyPrincipal caller = authenticator.authenticate(key).orElseThrow();
        var request = new ChatRequest("claude-opus-5-5", List.of(new Message(Role.USER, "hi")), 32, null, null,
                List.of(), false, false, null);
        var ctx = new RequestContext(UUID.randomUUID().toString(), System.nanoTime(), caller, request);
        List<Throwable> errors = new ArrayList<>();

        pipeline.run(ctx, new StreamSink() {
            @Override
            public void onToken(String text) {
            }

            @Override
            public void onComplete(Completion completion) {
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        });

        assertThat(errors).hasSize(1);
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(ctx.errorCode()).isEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
        // Connection refused is retryable: two attempts on Claude; the Ollama fallback (same unroutable port in the
        // test profile) adds two more only where its adapter exists.
        assertThat(ctx.attempts()).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(a -> assertThat(a.outcome()).isEqualTo(AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN));
        assertThat(ctx.attempts().subList(0, 2)).allSatisfy(a -> assertThat(a.provider()).isEqualTo("claude"));
        // Scoped to this request's id (TEAM_RULES): rows of other tests may still be arriving.
        Map<String, Object> row = jdbc.sql("SELECT * FROM request_log WHERE request_id = ?")
                .param(UUID.fromString(ctx.requestId())).query().singleRow();
        assertThat(row).containsEntry("request_id", UUID.fromString(ctx.requestId()))
                .containsEntry("tenant_id", "acme")
                .containsEntry("provider_used", ctx.servingAttempt().orElseThrow().provider())
                .containsEntry("status", "error")
                .containsEntry("error_code", "all_providers_failed");
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(503);
    }
}
