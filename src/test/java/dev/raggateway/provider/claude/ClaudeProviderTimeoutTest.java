package dev.raggateway.provider.claude;

import com.anthropic.client.AnthropicClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.provider.ProviderException.Kind;
import dev.raggateway.provider.claude.ClaudeProviderTest.RecordingSink;
import dev.raggateway.support.SilentHttpServer;
import dev.raggateway.support.SlowSseServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * First-token and idle timeouts of the Claude adapter (t03, AC-t03-2 adapter part, D-06). Timeouts are a few
 * hundred milliseconds so the tests stay fast. Each timeout must be retryable and fire within the configured
 * time (plus a 1 s scheduling margin). Once the response has started, the watchdog closes it and the raw-socket
 * servers see the connection close at the deadline. Before the headers, the call is abandoned at the deadline
 * and its connection closed when the late headers arrive or at the socket read backstop (first-token + idle);
 * see the {@link ClaudeProvider} class comment.
 *
 * <p>The first SDK call in a JVM takes about half a second (class loading, OkHttp start-up), longer than the
 * timeouts used here, so {@link #warmUp()} makes one successful call before the tests.
 */
class ClaudeProviderTimeoutTest {

    private static final String MODEL = ClaudeProviderTest.MODEL;
    private static final Duration MARGIN = Duration.ofSeconds(1);

    private static WireMockServer wireMock;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeAll
    static void warmUp() throws Exception {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream").withBody(ClaudeSse.reply(MODEL, 5, 1, "warm"))));
        var test = new ClaudeProviderTimeoutTest();
        try {
            test.provider(wireMock.baseUrl(), Duration.ofSeconds(10), Duration.ofSeconds(10))
                    .stream(ClaudeProviderTest.request("hi"), new RecordingSink());
        } finally {
            test.closeClients();
        }
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void closeClients() throws Exception {
        for (AutoCloseable c : closeables) c.close();
    }

    private ClaudeProvider provider(String baseUrl, Duration firstToken, Duration idle) {
        var models = Map.of(MODEL, new ModelProperties("claude", MODEL, new BigDecimal("4"), new BigDecimal("20")));
        var props = new ProviderProperties(baseUrl, "test-key-not-secret", Duration.ofSeconds(1), firstToken, idle);
        AnthropicClient client = ClaudeClientConfig.client(props);
        closeables.add(client::close);
        return new ClaudeProvider(client, new GatewayProperties(1024, 8192, models, Map.of("claude", props)));
    }

    /** Frames of {@code sse} from index {@code from} (inclusive) to {@code to} (exclusive), as one frame. */
    private static String join(List<String> frames, int from, int to) {
        return String.join("", frames.subList(from, to));
    }

    @Test
    void ac_t03_2_noResponseHeadersWithinTheFirstTokenTimeoutIsARetryableTimeoutInTime() throws Exception {
        Duration firstToken = Duration.ofMillis(300);
        Duration idle = Duration.ofMillis(500);
        try (var upstream = SilentHttpServer.silent()) {
            ClaudeProvider provider = provider(upstream.baseUrl(), firstToken, idle);
            var sink = new RecordingSink();

            long start = System.nanoTime();
            ProviderException e = catchThrowableOfType(ProviderException.class,
                    () -> provider.stream(ClaudeProviderTest.request("hi"), sink));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
            assertThat(e.retryable()).isTrue();
            assertThat(e.fallbackAllowed()).isTrue();
            assertThat(elapsed).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(MARGIN));
            assertThat(sink.tokens).isEmpty();
            assertThat(sink.completion).isNull();
            assertThat(upstream.requests()).as("the request reached the upstream").isEqualTo(1);
            // Never answered: the socket read backstop (first-token + idle) closes the abandoned connection.
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(3));
            assertThat(closedAt).as("abandoned connection closed by the read backstop").isPositive();
            assertThat(Duration.ofNanos(closedAt - start)).isLessThan(firstToken.plus(idle).plus(MARGIN));
        }
    }

    @Test
    void responseHeadersArrivingAfterTheFirstTokenTimeoutAreClosedAtOnce() throws Exception {
        Duration firstToken = Duration.ofMillis(300);
        String messageStart = ClaudeSse.start(MODEL, 10).frames().get(0);
        try (var upstream = SilentHttpServer.answeringAfter(Duration.ofMillis(800), "text/event-stream", messageStart)) {
            // idle 5 s: the read backstop (5.3 s) is far away, so a prompt close comes from the adapter.
            ClaudeProvider provider = provider(upstream.baseUrl(), firstToken, Duration.ofSeconds(5));

            long start = System.nanoTime();
            ProviderException e = catchThrowableOfType(ProviderException.class,
                    () -> provider.stream(ClaudeProviderTest.request("hi"), new RecordingSink()));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
            assertThat(elapsed).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(MARGIN));
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(3));
            assertThat(closedAt).as("late response closed by the adapter").isPositive();
            assertThat(upstream.answeredAtNanos()).isPositive();
            assertThat(Duration.ofNanos(closedAt - upstream.answeredAtNanos())).isLessThan(MARGIN);
        }
    }

    @Test
    void wireMockFixedDelayLongerThanTheFirstTokenTimeoutTimesOutWithinTheConfiguredTime() {
        wireMock.resetAll();
        wireMock.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(ClaudeSse.reply(MODEL, 5, 1, "late"))
                .withFixedDelay(5_000)));
        Duration firstToken = Duration.ofMillis(400);
        ClaudeProvider provider = provider(wireMock.baseUrl(), firstToken, Duration.ofSeconds(5));

        long start = System.nanoTime();
        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider.stream(ClaudeProviderTest.request("hi"), new RecordingSink()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(e).isNotNull();
        assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
        assertThat(e.retryable()).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(MARGIN));
    }

    @Test
    void headersAndMessageStartThenSilenceIsAFirstTokenTimeout() throws Exception {
        // message_start and ping are not content (D-06): they must not stop the first-token clock.
        String frame = join(ClaudeSse.start(MODEL, 10).ping().frames(), 0, 2);
        Duration firstToken = Duration.ofMillis(300);
        try (var upstream = new SlowSseServer(List.of(frame), Duration.ofMillis(10))) {
            ClaudeProvider provider = provider(upstream.baseUrl(), firstToken, Duration.ofSeconds(5));
            var sink = new RecordingSink();

            long start = System.nanoTime();
            ProviderException e = catchThrowableOfType(ProviderException.class,
                    () -> provider.stream(ClaudeProviderTest.request("hi"), sink));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(e.kind()).isEqualTo(Kind.FIRST_TOKEN_TIMEOUT);
            assertThat(e.retryable()).isTrue();
            assertThat(elapsed).isGreaterThanOrEqualTo(firstToken).isLessThan(firstToken.plus(MARGIN));
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(2));
            assertThat(closedAt).as("upstream connection closed by the watchdog").isPositive();
            assertThat(Duration.ofNanos(closedAt - start)).isLessThan(firstToken.plus(MARGIN));
            assertThat(sink.tokens).isEmpty();
        }
    }

    @Test
    void longGapMidStreamIsAnIdleTimeoutThatClosesTheUpstreamConnection() throws Exception {
        List<String> frames = ClaudeSse.start(MODEL, 10).textBlockStart().text("Hel").text("lo").blockStop()
                .end("end_turn", 2).frames();
        // message_start, content_block_start, "Hel" at once; then a 5 s pause before "lo".
        List<String> paced = List.of(join(frames, 0, 3), join(frames, 3, frames.size()));
        Duration idle = Duration.ofMillis(300);
        try (var upstream = new SlowSseServer(paced, Duration.ofSeconds(5))) {
            ClaudeProvider provider = provider(upstream.baseUrl(), Duration.ofSeconds(2), idle);
            var sink = new RecordingSink();

            long start = System.nanoTime();
            ProviderException e = catchThrowableOfType(ProviderException.class,
                    () -> provider.stream(ClaudeProviderTest.request("hi"), sink));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(e).isNotNull();
            assertThat(e.kind()).isEqualTo(Kind.IDLE_TIMEOUT);
            assertThat(e.retryable()).as("retryable; the router still refuses to retry after a token").isTrue();
            assertThat(sink.tokens).containsExactly("Hel");
            assertThat(sink.completion).isNull();
            assertThat(elapsed).isGreaterThanOrEqualTo(idle).isLessThan(idle.plus(MARGIN));
            long closedAt = upstream.awaitClientClose(Duration.ofSeconds(2));
            assertThat(closedAt).as("upstream connection closed by the watchdog").isPositive();
            assertThat(Duration.ofNanos(closedAt - start)).isLessThan(idle.plus(MARGIN));
            assertThat(upstream.framesWritten()).as("cut off before the rest of the stream").isEqualTo(1);
        }
    }

    @Test
    void thinkingBlockStartIsTheFirstTokenSoALongSilentThinkingPhaseDoesNotTimeOut() throws Exception {
        // D-06: first content event = content_block_start of any block. The thinking block starts at once,
        // then 700 ms pass (longer than the 300 ms first-token timeout, shorter than the 2 s idle timeout).
        List<String> frames = ClaudeSse.start(MODEL, 10).thinkingBlock().textBlockStart().text("answer").blockStop()
                .end("end_turn", 40).frames();
        List<String> paced = List.of(join(frames, 0, 2), join(frames, 2, frames.size()));
        try (var upstream = new SlowSseServer(paced, Duration.ofMillis(700))) {
            ClaudeProvider provider = provider(upstream.baseUrl(), Duration.ofMillis(300), Duration.ofSeconds(2));
            var sink = new RecordingSink();

            provider.stream(ClaudeProviderTest.request("hi"), sink);

            assertThat(sink.tokens).containsExactly("answer");
            assertThat(sink.completion).isNotNull();
        }
    }

    @Test
    void steadyStreamLongerThanTheFirstTokenTimeoutCompletesBecauseEachEventMovesTheIdleDeadline() throws Exception {
        ClaudeSse sse = ClaudeSse.start(MODEL, 10).textBlockStart();
        for (int i = 0; i < 8; i++) sse.text("t" + i);
        List<String> frames = sse.blockStop().end("end_turn", 8).frames();
        // 12 frames, 120 ms apart: about 1.3 s in all, over the 400 ms first-token and 400 ms idle timeouts.
        try (var upstream = new SlowSseServer(frames, Duration.ofMillis(120))) {
            ClaudeProvider provider = provider(upstream.baseUrl(), Duration.ofMillis(400), Duration.ofMillis(400));
            var sink = new RecordingSink();

            provider.stream(ClaudeProviderTest.request("hi"), sink);

            assertThat(sink.tokens).hasSize(8);
            assertThat(sink.completion).isNotNull();
        }
    }
}
