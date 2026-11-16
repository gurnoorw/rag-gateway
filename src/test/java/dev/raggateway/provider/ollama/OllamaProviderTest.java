package dev.raggateway.provider.ollama;

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
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.provider.ProviderException.Kind;
import dev.raggateway.support.OllamaNdjson;
import dev.raggateway.support.SilentHttpServer;
import dev.raggateway.support.SlowSseServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Ollama adapter against WireMock speaking {@code /api/chat} NDJSON ({@link OllamaNdjson}; Ollama itself is not
 * reachable here, D-21), plus the raw-socket servers for timeouts and cancellation, which must close the upstream
 * connection (t03, AC-t03-2 adapter part, C23).
 */
class OllamaProviderTest {

    static final String MODEL = "llama3.2:1b";
    private static final Duration MARGIN = Duration.ofSeconds(1);

    static WireMockServer wireMock;
    private final List<HttpClient> clients = new ArrayList<>();
    private OllamaProvider provider;

    @BeforeAll
    static void startWireMockAndWarmUp() throws Exception {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        // The first JDK HttpClient call in a JVM is slow (class loading); keep it out of the timing tests.
        wireMock.stubFor(post("/api/chat").willReturn(ndjson(OllamaNdjson.reply(MODEL, 1, 1, "warm"))));
        var test = new OllamaProviderTest();
        try {
            test.provider(wireMock.baseUrl(), Duration.ofSeconds(10), Duration.ofSeconds(10))
                    .stream(request("hi"), new RecordingSink());
        } finally {
            test.closeClients();
        }
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        provider = provider(wireMock.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @AfterEach
    void closeClients() {
        clients.forEach(HttpClient::close);
    }

    private OllamaProvider provider(String baseUrl, Duration firstToken, Duration idle) {
        var models = Map.of(
                MODEL, new ModelProperties("ollama", MODEL, BigDecimal.ZERO, BigDecimal.ZERO),
                "claude-opus-5-5", new ModelProperties("claude", "claude-opus-5-5", BigDecimal.ONE, BigDecimal.ONE));
        var props = new ProviderProperties(baseUrl, null, Duration.ofSeconds(1), firstToken, idle);
        HttpClient http = OllamaClientConfig.client(props);
        clients.add(http);
        return new OllamaProvider(http, new GatewayProperties(1024, 8192, models, Map.of("ollama", props)));
    }

    static ChatRequest request(String userText) {
        return new ChatRequest(MODEL, List.of(new Message(Role.USER, userText)), 128, null, null, List.of(), true,
                false, null);
    }

    private static ResponseDefinitionBuilder ndjson(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", OllamaNdjson.CONTENT_TYPE).withBody(body);
    }

    private static void stub(ResponseDefinitionBuilder response) {
        wireMock.stubFor(post("/api/chat").willReturn(response));
    }

    /** NDJSON body split into one frame per line, for the raw-socket server. */
    private static List<String> lines(String body) {
        return Arrays.stream(body.split("\n")).map(l -> l + "\n").toList();
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

    // ---- request and stream mapping ----

    @Test
    void namesAndSupportsOnlyOllamaCatalogModels() {
        assertThat(provider.name()).isEqualTo("ollama");
        assertThat(provider.supports(MODEL)).isTrue();
        assertThat(provider.supports("claude-opus-5-5")).isFalse();
        assertThat(provider.supports("gpt-4o")).isFalse();
    }

    @Test
    void ndjsonLinesBecomeTokensAndTheDoneLineGivesFinishUsageAndModel() throws Exception {
        stub(ndjson(OllamaNdjson.start(MODEL).text("Hel").text("lo, ").text("").text("world").end("stop", 26, 4).body()));
        var sink = new RecordingSink();

        provider.stream(request("hi"), sink);

        assertThat(sink.tokens).containsExactly("Hel", "lo, ", "world");
        assertThat(sink.completion).isEqualTo(new Completion(FinishReason.STOP, new Usage(26, 4), MODEL));
        assertThat(sink.errors).isZero();
    }

    @Test
    void doneReasonLengthIsFinishReasonLength() throws Exception {
        stub(ndjson(OllamaNdjson.start(MODEL).text("cut").end("length", 5, 128).body()));
        var sink = new RecordingSink();

        provider.stream(request("hi"), sink);

        assertThat(sink.completion.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(sink.completion.usage()).isEqualTo(new Usage(5, 128));
    }

    @Test
    void requestCarriesModelRolesStreamAndForwardsSamplingStopAndMaxTokensAsOptions() throws Exception {
        stub(ndjson(OllamaNdjson.reply(MODEL, 5, 1, "ok")));
        var request = new ChatRequest(MODEL, List.of(
                new Message(Role.SYSTEM, "Be brief."),
                new Message(Role.USER, "Hi"),
                new Message(Role.ASSISTANT, "Hello."),
                new Message(Role.USER, "Bye")), 64, 0.0, 0.9, List.of("END"), true, false, null);

        provider.stream(request, new RecordingSink());

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"model":"llama3.2:1b","stream":true,
                         "messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"Hi"},
                                     {"role":"assistant","content":"Hello."},{"role":"user","content":"Bye"}],
                         "options":{"temperature":0.0,"top_p":0.9,"num_predict":64,"stop":["END"]}}""")));
    }

    @Test
    void unsetSamplingParametersAndEmptyStopAreNotSent() throws Exception {
        stub(ndjson(OllamaNdjson.reply(MODEL, 5, 1, "ok")));

        provider.stream(request("hi"), new RecordingSink());

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat")).withRequestBody(equalToJson("""
                {"model":"llama3.2:1b","stream":true,"messages":[{"role":"user","content":"hi"}],
                 "options":{"num_predict":128}}""")));
    }

    // ---- errors ----

    @Test
    void http404ModelNotPulledIsNotRetryableAndAllowsNoFallback() {
        stub(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody(OllamaNdjson.errorBody("model \"llama3.2:1b\" not found, try pulling it first")));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider.stream(request("hi"), new RecordingSink()));

        assertThat(e.kind()).isEqualTo(Kind.HTTP_STATUS);
        assertThat(e.httpStatus()).isEqualTo(404);
        assertThat(e.retryable()).isFalse();
        assertThat(e.fallbackAllowed()).as("a configuration fault, not a provider outage").isFalse();
        assertThat(e).hasMessageContaining("not found");
    }

    @Test
    void http400IsNotRetryable() {
        stub(aResponse().withStatus(400).withBody(OllamaNdjson.errorBody("invalid options")));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider.stream(request("hi"), new RecordingSink()));

        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    void http500IsRetryable() {
        stub(aResponse().withStatus(500).withBody(OllamaNdjson.errorBody("llama runner process has terminated")));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider.stream(request("hi"), new RecordingSink()));

        assertThat(e.kind()).isEqualTo(Kind.HTTP_STATUS);
        assertThat(e.httpStatus()).isEqualTo(500);
        assertThat(e.retryable()).isTrue();
        assertThat(e.retryAfter()).isEmpty();
    }

    @Test
    void http429IsRetryableWithRetryAfterSeconds() {
        stub(aResponse().withStatus(429).withHeader("Retry-After", "3").withBody(OllamaNdjson.errorBody("busy")));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider.stream(request("hi"), new RecordingSink()));

        assertThat(e.httpStatus()).isEqualTo(429);
        assertThat(e.retryable()).isTrue();
        assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));
    }

    @Test
    void errorLineMidStreamIsARetryableFailureAfterTheTokens() {
        stub(ndjson(OllamaNdjson.start(MODEL).text("Hel").error("an error was encountered while running the model")
                .body()));
        var sink = new RecordingSink();

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider.stream(request("hi"), sink));

        assertThat(sink.tokens).containsExactly("Hel");
        assertThat(sink.completion).isNull();
        assertThat(e.kind()).isEqualTo(Kind.HTTP_STATUS);
        assertThat(e.retryable()).as("retryable, but the router never retries after a token").isTrue();
        assertThat(e).hasMessageContaining("error was encountered");
    }

    @Test
    void streamEndingWithoutDoneIsMalformed() {
        stub(ndjson(OllamaNdjson.start(MODEL).text("Hel").text("lo").body()));
        var sink = new RecordingSink();

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider.stream(request("hi"), sink));

        assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE);
        assertThat(sink.tokens).containsExactly("Hel", "lo");
        assertThat(sink.completion).isNull();
    }

    @Test
    void lineThatIsNotJsonIsMalformed() {
        stub(ndjson("{\"model\":\"llama3.2:1b\",\"message\":{\"content\":\"a\"},\"done\":false}\nnot json\n"));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider.stream(request("hi"), new RecordingSink()));

        assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE);
    }

    @Test
    void connectionRefusedIsARetryableConnectionFailure() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        OllamaProvider refused = provider("http://127.0.0.1:" + closedPort, Duration.ofSeconds(5), Duration.ofSeconds(5));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> refused.stream(request("hi"), new RecordingSink()));

        assertThat(e.kind()).isEqualTo(Kind.CONNECTION);
        assertThat(e.retryable()).isTrue();
        assertThat(e).hasMessageContaining("refused");
    }

    @Test
    void connectionResetIsARetryableConnectionFailure() {
        stub(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider.stream(request("hi"), new RecordingSink()));

        assertThat(e.kind()).isEqualTo(Kind.CONNECTION);
        assertThat(e.retryable()).isTrue();
    }

    // ---- timeouts (watchdog) ----

    @Test
    void ac_t03_2_noResponseWithinTheFirstTokenTimeoutIsARetryableTimeoutAndClosesTheConnection() throws Exception {
        Duration firstToken = Duration.ofMillis(300);
        try (var upstream = SilentHttpServer.silent()) {
            OllamaProvider slow = provider(upstream.baseUrl(), firstToken, Duration.ofSeconds(5));
            var sink = new RecordingSink();

            long start = System.nanoTime();
            ProviderException e = catchThrowableOfType(ProviderException.class, () -> slow.stream(request("hi"), sink));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
            assertThat(e.retryable()).isTrue();
            assertThat(e.fallbackAllowed()).isTrue();
            assertThat(elapsed).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(MARGIN));
            assertThat(upstream.requests()).as("the request reached the upstream").isEqualTo(1);
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(2));
            assertThat(closedAt).as("connection closed by the watchdog").isPositive();
            assertThat(Duration.ofNanos(closedAt - start)).isLessThan(firstToken.plus(MARGIN));
            assertThat(sink.tokens).isEmpty();
        }
    }

    @Test
    void wireMockFixedDelayLongerThanTheFirstTokenTimeoutTimesOutWithinTheConfiguredTime() {
        stub(ndjson(OllamaNdjson.reply(MODEL, 5, 1, "late")).withFixedDelay(5_000));
        Duration firstToken = Duration.ofMillis(400);
        OllamaProvider slow = provider(wireMock.baseUrl(), firstToken, Duration.ofSeconds(5));

        long start = System.nanoTime();
        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> slow.stream(request("hi"), new RecordingSink()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(e).isNotNull();
        assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
        assertThat(e.retryable()).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(MARGIN));
    }

    @Test
    void headersThenSilenceIsAFirstTokenTimeoutThatClosesTheConnection() throws Exception {
        Duration firstToken = Duration.ofMillis(300);
        try (var upstream = new SlowSseServer(List.of(), Duration.ofMillis(10))) {
            OllamaProvider slow = provider(upstream.baseUrl(), firstToken, Duration.ofSeconds(5));

            long start = System.nanoTime();
            ProviderException e = catchThrowableOfType(ProviderException.class,
                    () -> slow.stream(request("hi"), new RecordingSink()));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
            assertThat(elapsed).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(MARGIN));
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(2));
            assertThat(closedAt).as("connection closed by the watchdog").isPositive();
            assertThat(Duration.ofNanos(closedAt - start)).isLessThan(firstToken.plus(MARGIN));
        }
    }

    @Test
    void longGapMidStreamIsAnIdleTimeoutThatClosesTheConnection() throws Exception {
        List<String> frames = lines(OllamaNdjson.start(MODEL).text("Hel").text("lo").end("stop", 5, 2).body());
        // "Hel" at once, then a 5 s pause before "lo".
        Duration idle = Duration.ofMillis(300);
        try (var upstream = new SlowSseServer(frames, Duration.ofSeconds(5))) {
            OllamaProvider slow = provider(upstream.baseUrl(), Duration.ofSeconds(2), idle);
            var sink = new RecordingSink();

            long start = System.nanoTime();
            ProviderException e = catchThrowableOfType(ProviderException.class, () -> slow.stream(request("hi"), sink));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(e.kind()).isEqualTo(Kind.IDLE_TIMEOUT);
            assertThat(e.retryable()).isTrue();
            assertThat(sink.tokens).containsExactly("Hel");
            assertThat(sink.completion).isNull();
            assertThat(elapsed).isGreaterThanOrEqualTo(idle).isLessThan(idle.plus(MARGIN));
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(2));
            assertThat(closedAt).as("connection closed by the watchdog").isPositive();
            assertThat(Duration.ofNanos(closedAt - start)).isLessThan(idle.plus(MARGIN));
            assertThat(upstream.framesWritten()).isEqualTo(1);
        }
    }

    @Test
    void steadyStreamLongerThanTheFirstTokenTimeoutCompletes() throws Exception {
        OllamaNdjson body = OllamaNdjson.start(MODEL);
        for (int i = 0; i < 8; i++) body.text("t" + i);
        // 9 lines, 120 ms apart: about 1 s in all, over the 400 ms first-token and idle timeouts.
        try (var upstream = new SlowSseServer(lines(body.end("stop", 5, 8).body()), Duration.ofMillis(120))) {
            OllamaProvider slow = provider(upstream.baseUrl(), Duration.ofMillis(400), Duration.ofMillis(400));
            var sink = new RecordingSink();

            slow.stream(request("hi"), sink);

            assertThat(sink.tokens).hasSize(8);
            assertThat(sink.completion).isEqualTo(new Completion(FinishReason.STOP, new Usage(5, 8), MODEL));
        }
    }

    // ---- client disconnect (C23) ----

    @Test
    void sinkDisconnectClosesTheUpstreamConnectionWithinTwoSecondsAndRethrowsUnchanged() throws Exception {
        OllamaNdjson body = OllamaNdjson.start(MODEL);
        for (int i = 0; i < 30; i++) body.text("tok" + i + " ");
        List<String> frames = lines(body.end("stop", 5, 30).body());
        try (var upstream = new SlowSseServer(frames, Duration.ofMillis(200))) {
            OllamaProvider streaming = provider(upstream.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5));
            var disconnect = new ClientDisconnectedException(new IOException("Broken pipe"));
            List<String> received = new ArrayList<>();
            StreamSink sink = new StreamSink() {
                @Override
                public void onToken(String text) {
                    if (received.size() == 2) throw disconnect;
                    received.add(text);
                }

                @Override
                public void onComplete(Completion completion) {
                    throw new AssertionError("must not complete");
                }

                @Override
                public void onError(Throwable error) {
                    throw new AssertionError("adapter must not call onError");
                }
            };

            Throwable thrown = catchThrowable(() -> streaming.stream(request("hi"), sink));
            long thrownAt = System.nanoTime();

            assertThat(thrown).as("rethrown unchanged, never wrapped").isSameAs(disconnect);
            assertThat(received).containsExactly("tok0 ", "tok1 ");
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(2));
            assertThat(closedAt).as("upstream saw the connection close").isPositive();
            assertThat(Duration.ofNanos(closedAt - thrownAt)).isLessThan(Duration.ofSeconds(2));
            assertThat(upstream.framesWritten()).as("upstream was cut off early").isLessThan(frames.size());
            assertThat(upstream.requests()).isEqualTo(1);
        }
    }
}
