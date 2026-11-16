package dev.raggateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.error.RateLimitedException;
import dev.raggateway.pipeline.ClientDisconnectedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseResponseSinkTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final GatewayProperties PROPS = new GatewayProperties(1024, 8192, Map.of(
            "claude-haiku-5-5", new ModelProperties("claude", "claude-haiku-5-5", BigDecimal.ONE, BigDecimal.ONE),
            "local-llama", new ModelProperties("ollama", "llama3.2:1b", BigDecimal.ZERO, BigDecimal.ZERO)),
            Map.of());
    static final Completion DONE = new Completion(FinishReason.STOP, new Usage(10, 2), "claude-haiku-5-5");

    private final CapturingResponse response = new CapturingResponse();

    static RequestContext ctx(boolean includeUsage) {
        var tenant = new Tenant("t1", "Tenant 1", 10_000, BigDecimal.TEN, Set.of());
        var caller = new ApiKeyPrincipal("k1", tenant, Set.of(), false);
        var request = new ChatRequest("claude-haiku-5-5", List.of(new Message(Role.USER, "q")), 10,
                null, null, List.of(), true, includeUsage, null);
        return new RequestContext("req-1", System.nanoTime(), caller, request);
    }

    private SseResponseSink sink(RequestContext ctx) {
        return new SseResponseSink(response, ctx, JSON, PROPS);
    }

    /** The {@code data:} payloads, in order. */
    private List<String> events() {
        return Arrays.stream(response.body().split("\n\n"))
                .filter(e -> !e.isEmpty())
                .peek(e -> assertThat(e).startsWith("data: "))
                .map(e -> e.substring("data: ".length()))
                .toList();
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void streamFormatIsOpenAiChunksThenFinishChunkThenDone() throws Exception {
        var ctx = ctx(false);
        var sink = sink(ctx);

        sink.onToken("Hel");
        sink.onToken("lo");
        sink.onComplete(DONE);

        List<String> events = events();
        assertThat(events).hasSize(4);
        JsonNode first = json(events.get(0));
        assertThat(first.get("id").asText()).isEqualTo("chatcmpl-req-1");
        assertThat(first.get("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(first.get("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(first.get("created").asLong()).isPositive();
        assertThat(first.at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        assertThat(first.at("/choices/0/delta/content").asText()).isEqualTo("Hel");
        assertThat(first.at("/choices/0/finish_reason").isNull()).isTrue();
        assertThat(first.has("usage")).isFalse();
        JsonNode second = json(events.get(1));
        assertThat(second.at("/choices/0/delta").has("role")).isFalse();
        assertThat(second.at("/choices/0/delta/content").asText()).isEqualTo("lo");
        JsonNode last = json(events.get(2));
        assertThat(last.at("/choices/0/delta").isEmpty()).isTrue();
        assertThat(last.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        assertThat(events.get(3)).isEqualTo("[DONE]");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/event-stream");
        assertThat(response.closed()).isTrue();
        assertThat(ctx.firstTokenSent()).isTrue();
        assertThat(sink.terminated()).isTrue();
    }

    @Test
    void headersComeFromTheContextAtTheFirstByte() {
        var ctx = ctx(false);
        sink(ctx).onToken("x");

        assertThat(response.getHeader(GatewayHeaders.PROVIDER)).isEqualTo("claude");
        assertThat(response.getHeader(GatewayHeaders.MODEL)).isEqualTo("claude-haiku-5-5");
        assertThat(response.getHeader(GatewayHeaders.CACHE)).isEqualTo("NOT_ELIGIBLE");
        assertThat(response.getHeader(GatewayHeaders.ROUTE_RULE)).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void cacheHitIsServedByCacheWithTheRequestModel() {
        var ctx = ctx(false);
        ctx.setCacheStatus(CacheStatus.HIT);
        ctx.setRouteRule("short-prompt");
        sink(ctx).onToken("x");

        assertThat(response.getHeader(GatewayHeaders.PROVIDER)).isEqualTo("cache");
        assertThat(response.getHeader(GatewayHeaders.MODEL)).isEqualTo("claude-haiku-5-5");
        assertThat(response.getHeader(GatewayHeaders.CACHE)).isEqualTo("HIT");
        assertThat(response.getHeader(GatewayHeaders.ROUTE_RULE)).isEqualTo("short-prompt");
    }

    @Test
    void succeededServingAttemptWinsOverTheCatalog() {
        var ctx = ctx(false);
        ctx.recordAttempt(new Attempt("ollama", "local-llama", true, AttemptOutcome.SUCCEEDED, new Usage(1, 1),
                false, 1, 1, null));
        sink(ctx).onToken("x");

        assertThat(response.getHeader(GatewayHeaders.PROVIDER)).isEqualTo("ollama");
        assertThat(response.getHeader(GatewayHeaders.MODEL)).isEqualTo("local-llama");
    }

    @Test
    void failedEarlierAttemptIsNotReportedAsTheServer() {
        var ctx = ctx(false);
        ctx.recordAttempt(new Attempt("ollama", "local-llama", false, AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN,
                Usage.ZERO, true, 1, -1, "HTTP_STATUS"));
        sink(ctx).onToken("x");

        assertThat(response.getHeader(GatewayHeaders.PROVIDER)).isEqualTo("claude");
    }

    @Test
    void usageChunkIsSentBeforeDoneWhenIncludeUsage() throws Exception {
        var sink = sink(ctx(true));
        sink.onToken("a");
        sink.onComplete(DONE);

        List<String> events = events();
        assertThat(events).hasSize(4);
        JsonNode usage = json(events.get(2));
        assertThat(usage.get("choices")).isEmpty();
        assertThat(usage.at("/usage/prompt_tokens").asInt()).isEqualTo(10);
        assertThat(usage.at("/usage/completion_tokens").asInt()).isEqualTo(2);
        assertThat(usage.at("/usage/total_tokens").asInt()).isEqualTo(12);
        assertThat(events.get(3)).isEqualTo("[DONE]");
    }

    @Test
    void everyTokenIsFlushedImmediately() {
        var sink = sink(ctx(false));
        sink.onToken("first");

        assertThat(response.flushedBody()).contains("\"content\":\"first\"").endsWith("\n\n");
        sink.onToken("second");
        assertThat(response.flushedBody()).contains("\"content\":\"second\"");
    }

    @Test
    void completionWithoutTokensStillCarriesRoleAndEnds() throws Exception {
        sink(ctx(false)).onComplete(new Completion(FinishReason.CONTENT_FILTER, Usage.ZERO, "claude-haiku-5-5"));

        List<String> events = events();
        assertThat(events).hasSize(2);
        assertThat(json(events.get(0)).at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        assertThat(json(events.get(0)).at("/choices/0/finish_reason").asText()).isEqualTo("content_filter");
        assertThat(events.get(1)).isEqualTo("[DONE]");
    }

    @Test
    void errorBeforeFirstTokenIsHttpStatusWithJsonBody() throws Exception {
        var ctx = ctx(false);
        sink(ctx).onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "upstream failed"));

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentLength()).isEqualTo(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        JsonNode body = json(response.body());
        assertThat(body.at("/error/message").asText()).isEqualTo("upstream failed");
        assertThat(body.at("/error/type").asText()).isEqualTo("api_error");
        assertThat(body.at("/error/code").asText()).isEqualTo("provider_error");
        assertThat(body.at("/error/request_id").asText()).isEqualTo("req-1");
        assertThat(response.body()).doesNotStartWith("data:");
        assertThat(ctx.firstTokenSent()).isFalse();
    }

    @Test
    void rateLimitBeforeFirstTokenCarriesRetryAfter() {
        sink(ctx(false)).onError(new RateLimitedException(1500));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
    }

    @Test
    void unexpectedExceptionBecomesInternalErrorWithoutDetails() throws Exception {
        sink(ctx(false)).onError(new IllegalStateException("db password=hunter2"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(json(response.body()).at("/error/code").asText()).isEqualTo("internal_error");
        assertThat(response.body()).doesNotContain("hunter2");
    }

    @Test
    void errorAfterFirstTokenIsOneSseErrorEventAndNoDone() throws Exception {
        var sink = sink(ctx(false));
        sink.onToken("partial");
        sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "upstream broke"));

        List<String> events = events();
        assertThat(events).hasSize(2);
        JsonNode error = json(events.get(1));
        assertThat(error.at("/error/code").asText()).isEqualTo("provider_error");
        assertThat(error.at("/error/request_id").asText()).isEqualTo("req-1");
        assertThat(events).doesNotContain("[DONE]");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.closed()).isTrue();
    }

    @Test
    void writeFailureThrowsClientDisconnectedAndLaterEventsWriteNothing() {
        var sink = sink(ctx(false));
        sink.onToken("one");
        String before = response.body();
        response.failWrites();

        assertThatThrownBy(() -> sink.onToken("two")).isInstanceOf(ClientDisconnectedException.class);
        assertThat(sink.disconnected()).isTrue();
        assertThatThrownBy(() -> sink.onToken("three")).isInstanceOf(ClientDisconnectedException.class);
        sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "x"));
        assertThat(response.body()).isEqualTo(before);
    }

    @Test
    void writeFailureOnCompletionThrowsClientDisconnected() {
        var sink = sink(ctx(false));
        sink.onToken("one");
        response.failWrites();

        assertThatThrownBy(() -> sink.onComplete(DONE)).isInstanceOf(ClientDisconnectedException.class);
    }

    @Test
    void secondTerminalEventIsIgnored() {
        var sink = sink(ctx(false));
        sink.onToken("a");
        sink.onComplete(DONE);
        String body = response.body();

        sink.onError(new GatewayException(ErrorCode.PROVIDER_ERROR, "late"));
        sink.onComplete(DONE);

        assertThat(response.body()).isEqualTo(body);
    }
}
