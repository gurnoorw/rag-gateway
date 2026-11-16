package dev.raggateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.auth.ApiKeyAuthenticator;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.FilterChain;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.GatewayPipeline;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP edge over a real server, with a stub pipeline (the real one is not part of this test): auth,
 * body mapping, the two t01 filters, request id, headers and the client sinks. The stub pipeline runs the
 * real filters and a scripted terminal, so "no provider call" means the terminal was never reached.
 */
@Import(ChatEdgeIT.StubConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ChatEdgeIT extends AbstractIntegrationTest {

    // Obviously fake keys, created for this test only.
    static final String KEY_A = "rgw_test_edge_key_alpha_not_secret";
    static final String UNKNOWN_KEY = "rgw_test_edge_key_unknown_not_secret";
    static final String MODEL = "claude-haiku-5-5";
    static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        ApiKeyAuthenticator stubAuthenticator() {
            Tenant tenant = new Tenant("tenant-a", "Tenant A", 100_000, BigDecimal.TEN, Set.of());
            ApiKeyPrincipal principal = new ApiKeyPrincipal("key-a", tenant, Set.of("eng"), false);
            return presented -> KEY_A.equals(presented) ? Optional.of(principal) : Optional.empty();
        }

        @Bean
        @Primary
        TokenEstimator stubEstimator() {
            return new TokenEstimator() {
                @Override
                public int estimateInput(ChatRequest request) {
                    return 1;
                }

                @Override
                public int estimate(String text) {
                    return 1;
                }
            };
        }

        @Bean
        @Primary
        StubPipeline stubPipeline(List<GatewayFilter> filters) {
            return new StubPipeline(filters);
        }
    }

    /** Runs the real filters in order, then a scripted terminal; follows the {@link GatewayPipeline} contract. */
    static final class StubPipeline implements GatewayPipeline {
        final List<GatewayFilter> filters;
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger terminalCalls = new AtomicInteger();
        final AtomicReference<RequestContext> lastCtx = new AtomicReference<>();
        final AtomicReference<String> mdcRequestId = new AtomicReference<>();
        volatile Terminal terminal;

        StubPipeline(List<GatewayFilter> filters) {
            List<GatewayFilter> sorted = new ArrayList<>(filters);
            AnnotationAwareOrderComparator.sort(sorted);
            this.filters = List.copyOf(sorted);
        }

        void reset() {
            runs.set(0);
            terminalCalls.set(0);
            lastCtx.set(null);
            mdcRequestId.set(null);
            terminal = (ctx, sink) -> {
                sink.onToken("Hel");
                sink.onToken("lo");
                sink.onComplete(new Completion(FinishReason.STOP, new Usage(5, 2), ctx.request().model()));
            };
        }

        @Override
        public void run(RequestContext ctx, StreamSink clientSink) {
            runs.incrementAndGet();
            lastCtx.set(ctx);
            mdcRequestId.set(MDC.get(RequestIdFilter.MDC_KEY));
            Terminal counting = (c, s) -> {
                terminalCalls.incrementAndGet();
                terminal.call(c, s);
            };
            try {
                new FilterChain(filters, counting).next(ctx, clientSink);
                ctx.finish(RequestOutcome.SUCCESS, null, 200);
            } catch (GatewayException e) {
                clientSink.onError(e);
                ctx.finish(RequestOutcome.ERROR, e.code(), e.code().httpStatus());
            } catch (ClientDisconnectedException e) {
                ctx.finish(RequestOutcome.CLIENT_CANCELLED, null, 200);
            }
        }
    }

    @Autowired
    StubPipeline pipeline;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void resetPipeline() {
        pipeline.reset();
    }

    private HttpResponse<String> post(String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) builder.headers(headers);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String chat(boolean stream) {
        return """
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"stream":%s}""".formatted(MODEL, stream);
    }

    private static String bearer(String key) {
        return "Bearer " + key;
    }

    private static void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.at("/error/code").asText()).isEqualTo(code);
        assertThat(body.at("/error/message").asText()).isNotBlank();
        assertThat(body.at("/error/type").asText()).isNotBlank();
        String requestId = response.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow();
        assertThat(body.at("/error/request_id").asText()).isEqualTo(requestId);
    }

    // ---- AC-t01-3: 401, nothing reaches the pipeline ----

    @Test
    void ac_t01_3_missingKeyIs401AndPipelineNotCalled() throws Exception {
        var response = post(chat(true));

        assertError(response, 401, "unauthorized");
        assertThat(JSON.readTree(response.body()).at("/error/type").asText()).isEqualTo("authentication_error");
        assertThat(pipeline.runs).hasValue(0);
    }

    @Test
    void ac_t01_3_unknownKeyIs401AndPipelineNotCalled() throws Exception {
        assertError(post(chat(true), "Authorization", bearer(UNKNOWN_KEY)), 401, "unauthorized");
        assertThat(pipeline.runs).hasValue(0);
    }

    @Test
    void ac_t01_3_nonBearerOrEmptyKeyIs401() throws Exception {
        assertError(post(chat(false), "Authorization", "Basic " + KEY_A), 401, "unauthorized");
        assertError(post(chat(false), "Authorization", "Bearer   "), 401, "unauthorized");
        assertThat(pipeline.runs).hasValue(0);
    }

    @Test
    void ac_t01_3_authIsCheckedBeforeTheBody() throws Exception {
        assertError(post("{not json"), 401, "unauthorized");
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        assertThat(post(chat(false), "Authorization", "bearer " + KEY_A).statusCode()).isEqualTo(200);
    }

    // ---- AC-t01-4: 400, no provider call ----

    @Test
    void ac_t01_4_malformedJsonIs400AndPipelineNotCalled() throws Exception {
        assertError(post("{\"model\":", "Authorization", bearer(KEY_A)), 400, "invalid_request");
        assertError(post("", "Authorization", bearer(KEY_A)), 400, "invalid_request");
        assertError(post("{\"model\":\"m\",\"messages\":\"hi\"}", "Authorization", bearer(KEY_A)), 400, "invalid_request");
        assertThat(pipeline.runs).hasValue(0);
    }

    @Test
    void ac_t01_4_emptyMessagesIs400AndPipelineNotCalled() throws Exception {
        var response = post("{\"model\":\"" + MODEL + "\",\"messages\":[]}", "Authorization", bearer(KEY_A));

        assertError(response, 400, "invalid_request");
        assertThat(JSON.readTree(response.body()).at("/error/type").asText()).isEqualTo("invalid_request_error");
        assertThat(pipeline.runs).hasValue(0);
    }

    @Test
    void ac_t01_4_unsupportedFeatureIs400() throws Exception {
        assertError(post("""
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"n":3}""".formatted(MODEL),
                "Authorization", bearer(KEY_A)), 400, "invalid_request");
        assertThat(pipeline.runs).hasValue(0);
    }

    @Test
    void unknownModelIs400AndNoProviderCall() throws Exception {
        assertError(post("""
                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}],"stream":true}""",
                "Authorization", bearer(KEY_A)), 400, "invalid_request");
        assertThat(pipeline.terminalCalls).hasValue(0);
    }

    @Test
    void autoIsResolvedBeforeTheTerminalAndTheRuleIsInTheHeader() throws Exception {
        // t06 replaced t01's "auto is 400": the starting rule "short" sends a 1-token prompt to Haiku.
        var response = post("""
                {"model":"auto","messages":[{"role":"user","content":"hi"}]}""", "Authorization", bearer(KEY_A));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(GatewayHeaders.ROUTE_RULE)).hasValue("short");
        assertThat(pipeline.terminalCalls).hasValue(1);
        assertThat(pipeline.lastCtx.get().request().model()).isEqualTo("claude-haiku-5-5");
        assertThat(pipeline.lastCtx.get().requestedModel()).isEqualTo("auto");
    }

    @Test
    void maxTokensAboveCapIs400AndNoProviderCall() throws Exception {
        assertError(post("""
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"max_tokens":100000}""".formatted(MODEL),
                "Authorization", bearer(KEY_A)), 400, "invalid_request");
        assertThat(pipeline.terminalCalls).hasValue(0);
    }

    @Test
    void errorBodyIsJsonEvenWhenTheClientAcceptsOnlyEventStream() throws Exception {
        assertError(post(chat(true), "Accept", "text/event-stream"), 401, "unauthorized");
    }

    // ---- success paths, headers, request id ----

    @Test
    void streamingRequestIsSseWithGatewayHeaders() throws Exception {
        var response = post(chat(true), "Authorization", bearer(KEY_A));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("text/event-stream"));
        String requestId = response.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow();
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(response.headers().firstValue(GatewayHeaders.PROVIDER)).hasValue("claude");
        assertThat(response.headers().firstValue(GatewayHeaders.MODEL)).hasValue(MODEL);
        assertThat(response.headers().firstValue(GatewayHeaders.CACHE)).hasValue("NOT_ELIGIBLE");
        assertThat(response.headers().firstValue(GatewayHeaders.ROUTE_RULE)).isEmpty();

        List<String> events = Arrays.stream(response.body().split("\n\n")).filter(e -> !e.isBlank()).toList();
        assertThat(events).allSatisfy(e -> assertThat(e).startsWith("data: "));
        assertThat(events).hasSize(4).last().isEqualTo("data: [DONE]");
        JsonNode first = JSON.readTree(events.get(0).substring(6));
        assertThat(first.get("id").asText()).isEqualTo("chatcmpl-" + requestId);
        assertThat(first.at("/choices/0/delta/content").asText()).isEqualTo("Hel");
        assertThat(JSON.readTree(events.get(2).substring(6)).at("/choices/0/finish_reason").asText()).isEqualTo("stop");

        RequestContext ctx = pipeline.lastCtx.get();
        assertThat(ctx.requestId()).isEqualTo(requestId);
        assertThat(ctx.tenant().id()).isEqualTo("tenant-a");
        assertThat(ctx.caller().keyId()).isEqualTo("key-a");
        assertThat(ctx.firstTokenSent()).isTrue();
    }

    @Test
    void streamWithIncludeUsageEndsWithUsageChunkThenDone() throws Exception {
        var response = post("""
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"stream":true,
                 "stream_options":{"include_usage":true}}""".formatted(MODEL), "Authorization", bearer(KEY_A));

        List<String> events = Arrays.stream(response.body().split("\n\n")).filter(e -> !e.isBlank()).toList();
        assertThat(events).hasSize(5).last().isEqualTo("data: [DONE]");
        assertThat(JSON.readTree(events.get(3).substring(6)).at("/usage/total_tokens").asInt()).isEqualTo(7);
    }

    @Test
    void nonStreamingRequestIsOneJsonCompletion() throws Exception {
        var response = post("""
                {"model":"%s","messages":[{"role":"user","content":"hi"}],"unknown_field":{"x":1}}""".formatted(MODEL),
                "Authorization", bearer(KEY_A));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(response.body().getBytes().length);
        JsonNode body = JSON.readTree(response.body());
        String requestId = response.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow();
        assertThat(body.get("id").asText()).isEqualTo("chatcmpl-" + requestId);
        assertThat(body.get("object").asText()).isEqualTo("chat.completion");
        assertThat(body.at("/choices/0/message/content").asText()).isEqualTo("Hello");
        assertThat(body.at("/usage/total_tokens").asInt()).isEqualTo(7);
        assertThat(response.headers().firstValue(GatewayHeaders.PROVIDER)).hasValue("claude");
    }

    @Test
    void incomingRequestIdIsIgnoredAndEveryRequestGetsANewOne() throws Exception {
        String supplied = UUID.randomUUID().toString();
        var first = post(chat(false), "Authorization", bearer(KEY_A), GatewayHeaders.REQUEST_ID, supplied);
        var second = post(chat(false), "Authorization", bearer(KEY_A), GatewayHeaders.REQUEST_ID, supplied);

        String id1 = first.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow();
        String id2 = second.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow();
        assertThat(id1).isNotEqualTo(supplied).isNotEqualTo(id2);
        assertThat(pipeline.lastCtx.get().requestId()).isEqualTo(id2);
    }

    @Test
    void requestIdIsInTheLoggingMdcWhileThePipelineRuns() throws Exception {
        var response = post(chat(false), "Authorization", bearer(KEY_A));

        assertThat(pipeline.mdcRequestId.get())
                .isEqualTo(response.headers().firstValue(GatewayHeaders.REQUEST_ID).orElseThrow());
    }

    @Test
    void cacheBypassHeaderIsCopiedToTheContext() throws Exception {
        post(chat(false), "Authorization", bearer(KEY_A), GatewayHeaders.CACHE_BYPASS, "true");
        assertThat(pipeline.lastCtx.get().cacheBypassRequested()).isTrue();

        post(chat(false), "Authorization", bearer(KEY_A));
        assertThat(pipeline.lastCtx.get().cacheBypassRequested()).isFalse();

        post(chat(false), "Authorization", bearer(KEY_A), GatewayHeaders.CACHE_BYPASS, "no");
        assertThat(pipeline.lastCtx.get().cacheBypassRequested()).isFalse();
    }

    // ---- pipeline errors through the real sinks ----

    @Test
    void pipelineErrorBeforeFirstTokenIsAnHttpStatus() throws Exception {
        pipeline.terminal = (ctx, sink) -> sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "upstream failed"));

        assertError(post(chat(true), "Authorization", bearer(KEY_A)), 502, "provider_error");
        assertError(post(chat(false), "Authorization", bearer(KEY_A)), 502, "provider_error");
    }

    @Test
    void pipelineErrorAfterFirstTokenIsAnSseErrorEventWithoutDone() throws Exception {
        pipeline.terminal = (ctx, sink) -> {
            sink.onToken("partial");
            sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "upstream broke"));
        };

        var response = post(chat(true), "Authorization", bearer(KEY_A));

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> events = Arrays.stream(response.body().split("\n\n")).filter(e -> !e.isBlank()).toList();
        assertThat(events).hasSize(2).noneMatch(e -> e.contains("[DONE]"));
        assertThat(JSON.readTree(events.get(1).substring(6)).at("/error/code").asText()).isEqualTo("provider_error");
    }

    @Test
    void ac_t01_8_presentedKeysAreNeverLogged(CapturedOutput output) throws Exception {
        post(chat(false), "Authorization", bearer(KEY_A));
        post(chat(false), "Authorization", bearer(UNKNOWN_KEY));
        post("{broken", "Authorization", bearer(UNKNOWN_KEY));

        assertThat(output.getAll()).doesNotContain(KEY_A).doesNotContain(UNKNOWN_KEY);
    }

    @Test
    void unknownV1PathStillRequiresAuth() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/nothing-here")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(response, 401, "unauthorized");
    }
}
