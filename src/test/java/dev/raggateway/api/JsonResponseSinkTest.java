package dev.raggateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.ClientDisconnectedException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static dev.raggateway.api.SseResponseSinkTest.JSON;
import static dev.raggateway.api.SseResponseSinkTest.PROPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonResponseSinkTest {

    private final CapturingResponse response = new CapturingResponse();

    private JsonResponseSink sink(RequestContext ctx) {
        return new JsonResponseSink(response, ctx, JSON, PROPS);
    }

    @Test
    void completionIsOneChatCompletionWithContentLength() throws Exception {
        var ctx = SseResponseSinkTest.ctx(false);
        var sink = sink(ctx);

        sink.onToken("Hel");
        sink.onToken("lo");
        assertThat(response.body()).isEmpty(); // buffered, nothing sent yet
        sink.onComplete(new Completion(FinishReason.LENGTH, new Usage(7, 2), "claude-haiku-5-5"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentLength()).isEqualTo(response.body().getBytes(StandardCharsets.UTF_8).length);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("id").asText()).isEqualTo("chatcmpl-req-1");
        assertThat(body.get("object").asText()).isEqualTo("chat.completion");
        assertThat(body.get("created").asLong()).isPositive();
        assertThat(body.get("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.at("/choices/0/index").asInt()).isZero();
        assertThat(body.at("/choices/0/message/role").asText()).isEqualTo("assistant");
        assertThat(body.at("/choices/0/message/content").asText()).isEqualTo("Hello");
        assertThat(body.at("/choices/0/finish_reason").asText()).isEqualTo("length");
        assertThat(body.at("/usage/prompt_tokens").asInt()).isEqualTo(7);
        assertThat(body.at("/usage/completion_tokens").asInt()).isEqualTo(2);
        assertThat(body.at("/usage/total_tokens").asInt()).isEqualTo(9);
        assertThat(response.getHeader(GatewayHeaders.PROVIDER)).isEqualTo("claude");
        assertThat(response.getHeader(GatewayHeaders.MODEL)).isEqualTo("claude-haiku-5-5");
        assertThat(response.getHeader(GatewayHeaders.CACHE)).isEqualTo("NOT_ELIGIBLE");
        assertThat(response.closed()).isTrue();
        assertThat(ctx.firstTokenSent()).isTrue();
        assertThat(sink.terminated()).isTrue();
    }

    @Test
    void upstreamModelIdIsReportedByItsCatalogName() throws Exception {
        sink(SseResponseSinkTest.ctx(false))
                .onComplete(new Completion(FinishReason.STOP, Usage.ZERO, "llama3.2:1b"));

        assertThat(JSON.readTree(response.body()).get("model").asText()).isEqualTo("local-llama");
        assertThat(response.getHeader(GatewayHeaders.PROVIDER)).isEqualTo("ollama");
        assertThat(response.getHeader(GatewayHeaders.MODEL)).isEqualTo("local-llama");
    }

    @Test
    void errorIsStatusAndJsonEvenAfterTokens() throws Exception {
        var sink = sink(SseResponseSinkTest.ctx(false));
        sink.onToken("partial");
        sink.onError(new GatewayException(ErrorCode.ALL_PROVIDERS_FAILED, "all providers failed"));

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.at("/error/code").asText()).isEqualTo("all_providers_failed");
        assertThat(body.at("/error/type").asText()).isEqualTo("api_error");
        assertThat(body.at("/error/request_id").asText()).isEqualTo("req-1");
        assertThat(response.body()).doesNotContain("partial");
    }

    @Test
    void writeFailureThrowsClientDisconnected() {
        var sink = sink(SseResponseSinkTest.ctx(false));
        sink.onToken("x");
        response.failWrites();

        assertThatThrownBy(() -> sink.onComplete(new Completion(FinishReason.STOP, Usage.ZERO, "claude-haiku-5-5")))
                .isInstanceOf(ClientDisconnectedException.class);
    }
}
