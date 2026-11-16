package dev.raggateway.provider.claude;

import com.anthropic.client.AnthropicClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.Role;
import dev.raggateway.core.Usage;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.provider.ProviderException.Kind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Claude adapter against WireMock speaking the Messages streaming format. No real API call. */
class ClaudeProviderTest {

    static final String MODEL = "claude-opus-5-5";
    static final String FAKE_KEY = "test-key-not-secret";

    static WireMockServer wireMock;
    AnthropicClient client;
    ClaudeProvider provider;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    static GatewayProperties properties(String baseUrl) {
        var models = Map.of(
                MODEL, new ModelProperties("claude", MODEL, new BigDecimal("4"), new BigDecimal("20")),
                "llama3.2:1b", new ModelProperties("ollama", "llama3.2:1b", BigDecimal.ZERO, BigDecimal.ZERO));
        var claude = new ProviderProperties(baseUrl, FAKE_KEY, Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(5));
        return new GatewayProperties(1024, 8192, models, Map.of("claude", claude));
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        GatewayProperties props = properties(wireMock.baseUrl());
        client = ClaudeClientConfig.client(props.providers().get("claude"));
        Clock clock = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);
        provider = new ClaudeProvider(client, props, clock);
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    static ChatRequest request(String... userText) {
        List<Message> messages = new ArrayList<>();
        for (String t : userText) messages.add(new Message(Role.USER, t));
        return new ChatRequest(MODEL, messages, 256, null, null, List.of(), true, false, null);
    }

    static final class RecordingSink implements StreamSink {
        final List<String> tokens = new ArrayList<>();
        Completion completion;
        int errors;

        @Override
        public void onToken(String text) {
            tokens.add(text);
        }

        @Override
        public void onComplete(Completion c) {
            completion = c;
        }

        @Override
        public void onError(Throwable error) {
            errors++;
        }
    }

    private void stubSse(String body) {
        wireMock.stubFor(post("/v1/messages").willReturn(sse(body)));
    }

    private static ResponseDefinitionBuilder sse(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(body);
    }

    @Test
    void namesAndSupportsOnlyClaudeCatalogModels() {
        assertThat(provider.name()).isEqualTo("claude");
        assertThat(provider.supports(MODEL)).isTrue();
        assertThat(provider.supports("llama3.2:1b")).isFalse();
        assertThat(provider.supports("gpt-4o")).isFalse();
    }

    @Test
    void multiChunkStreamIsForwardedInOrderWithUsageAndModel() throws Exception {
        stubSse(ClaudeSse.start(MODEL, 25).ping().textBlockStart()
                .text("Hel").text("lo, ").text("").text("world").blockStop()
                .end("end_turn", 7).body());
        var sink = new RecordingSink();

        provider.stream(request("hi"), sink);

        assertThat(sink.tokens).containsExactly("Hel", "lo, ", "world");
        assertThat(sink.completion).isEqualTo(new Completion(FinishReason.STOP, new Usage(25, 7), MODEL));
        assertThat(sink.errors).isZero();
    }

    @Test
    void thinkingBlockIsNotForwarded() throws Exception {
        stubSse(ClaudeSse.start(MODEL, 10).thinkingBlock().textBlockStart().text("answer").blockStop()
                .end("end_turn", 40).body());
        var sink = new RecordingSink();

        provider.stream(request("hi"), sink);

        assertThat(sink.tokens).containsExactly("answer");
        // thinking tokens are billed and included in output_tokens (D-06)
        assertThat(sink.completion.usage()).isEqualTo(new Usage(10, 40));
    }

    @ParameterizedTest
    @CsvSource({"end_turn,STOP", "stop_sequence,STOP", "max_tokens,LENGTH", "refusal,CONTENT_FILTER",
            "model_context_window_exceeded,LENGTH"})
    void stopReasonMapsToFinishReason(String stopReason, FinishReason expected) throws Exception {
        stubSse(ClaudeSse.start(MODEL, 3).textBlockStart().text("x").blockStop().end(stopReason, 2).body());
        var sink = new RecordingSink();

        provider.stream(request("hi"), sink);

        assertThat(sink.completion.finishReason()).isEqualTo(expected);
    }

    @Test
    void requestCarriesModelSystemTurnsStopAndMaxTokensButNoSamplingParameters() throws Exception {
        stubSse(ClaudeSse.reply(MODEL, 5, 1, "ok"));
        var request = new ChatRequest(MODEL, List.of(
                new Message(Role.SYSTEM, "Be brief."),
                new Message(Role.USER, "Hi"),
                new Message(Role.ASSISTANT, "Hello."),
                new Message(Role.SYSTEM, "Answer in English."),
                new Message(Role.USER, "Bye")),
                128, 0.7, 0.9, List.of("END"), true, true, null);

        provider.stream(request, new RecordingSink());

        var sent = wireMock.getAllServeEvents().get(0).getRequest();
        // SDK 3.3.0 presents a static API key as "Authorization: Bearer" (StaticAuthHeaders, checked with javap)
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + FAKE_KEY);
        assertThat(sent.getHeader("anthropic-version")).isEqualTo("2026-10-21");
        assertThat(sent.getBodyAsString()).doesNotContain("temperature", "top_p", "top_k", "thinking",
                "fallbacks", "effort");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(equalToJson("""
                        {"model":"claude-opus-5-5","max_tokens":128,"stream":true,
                         "system":"Be brief.\\n\\nAnswer in English.",
                         "stop_sequences":["END"],
                         "messages":[{"role":"user","content":"Hi"},
                                     {"role":"assistant","content":"Hello."},
                                     {"role":"user","content":"Bye"}]}""", false, false)));
    }

    @Test
    void http400IsNotRetryable() {
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}")));

        assertThatThrownBy(() -> provider.stream(request("hi"), new RecordingSink()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.HTTP_STATUS);
                    assertThat(e.httpStatus()).isEqualTo(400);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.retryAfter()).isEmpty();
                });
    }

    @Test
    void http429IsRetryableWithRetryAfterSeconds() {
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withHeader("retry-after", "3")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}")));

        assertThatThrownBy(() -> provider.stream(request("hi"), new RecordingSink()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(429);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));
                });
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))); // SDK retries are off
    }

    @Test
    void http500IsRetryableWithRetryAfterDate() {
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withHeader("Retry-After", "Mon, 16 Nov 2026 10:00:02 GMT")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"oops\"}}")));

        assertThatThrownBy(() -> provider.stream(request("hi"), new RecordingSink()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(500);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(2));
                });
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void http529OverloadedIsRetryable() {
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(529)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}")));

        assertThatThrownBy(() -> provider.stream(request("hi"), new RecordingSink()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(529);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void errorEventInsideTheStreamIsARetryableFailureAfterTheTokens() {
        stubSse(ClaudeSse.start(MODEL, 5).textBlockStart().text("par").error("overloaded_error", "busy").body());
        var sink = new RecordingSink();

        assertThatThrownBy(() -> provider.stream(request("hi"), sink))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.HTTP_STATUS);
                    assertThat(e.httpStatus()).isEqualTo(529);
                    assertThat(e.retryable()).isTrue();
                });
        assertThat(sink.tokens).containsExactly("par");
        assertThat(sink.completion).isNull();
    }

    @Test
    void streamWithoutStopReasonIsMalformed() {
        stubSse(ClaudeSse.start(MODEL, 5).textBlockStart().text("cut").body());
        var sink = new RecordingSink();

        assertThatThrownBy(() -> provider.stream(request("hi"), sink))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE));
        assertThat(sink.completion).isNull();
        assertThat(sink.errors).as("adapter never calls onError").isZero();
    }

    @Test
    void connectionResetIsARetryableConnectionFailure() {
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> provider.stream(request("hi"), new RecordingSink()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.CONNECTION);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.httpStatus()).isZero();
                });
    }

    @Test
    void connectionRefusedIsARetryableConnectionFailure() {
        GatewayProperties props = properties("http://127.0.0.1:9");
        AnthropicClient unreachable = ClaudeClientConfig.client(props.providers().get("claude"));
        try {
            var p = new ClaudeProvider(unreachable, props);
            assertThatThrownBy(() -> p.stream(request("hi"), new RecordingSink()))
                    .isInstanceOfSatisfying(ProviderException.class, e -> {
                        assertThat(e.kind()).isEqualTo(Kind.CONNECTION);
                        assertThat(e.retryable()).isTrue();
                    });
        } finally {
            unreachable.close();
        }
    }

    @Test
    void retryAfterParsing() {
        var h = com.anthropic.core.http.Headers.builder();
        assertThat(provider.retryAfter(h.build())).isNull();
        assertThat(provider.retryAfter(h.put("retry-after", "1.5").build())).isEqualTo(Duration.ofMillis(1500));
        assertThat(provider.retryAfter(com.anthropic.core.http.Headers.builder().put("retry-after", "soon").build()))
                .isNull();
        assertThat(provider.retryAfter(com.anthropic.core.http.Headers.builder()
                .put("retry-after", "Mon, 16 Nov 2026 09:59:00 GMT").build())).isEqualTo(Duration.ZERO);
    }
}
