package dev.raggateway;

import dev.raggateway.provider.claude.ClaudeSse;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.SlowSseServer;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AC-t01-9 end to end: a client that closes its connection mid-stream makes the gateway close its upstream
 * connection within 2 s, and one log row is written with status {@code client_cancelled}.
 *
 * <p>The upstream here is {@link SlowSseServer} instead of WireMock, because WireMock cannot report when a
 * client closes its connection; the stream it sends is the same Messages-API format.
 */
class ChatCompletionsCancelE2EIT extends AbstractIntegrationTest {

    static final String MODEL = "claude-haiku-5-5";
    static final String KEY = "rgw_test_cancel_key_" + UUID.randomUUID().toString().replace("-", "");
    static final List<String> FRAMES = frames();
    static final SlowSseServer CLAUDE = start();

    static List<String> frames() {
        ClaudeSse sse = ClaudeSse.start(MODEL, 12).textBlockStart();
        for (int i = 0; i < 40; i++) sse.text("tok" + i + " ");
        return sse.blockStop().end("end_turn", 40).frames();
    }

    static SlowSseServer start() {
        try {
            return new SlowSseServer(FRAMES, Duration.ofMillis(150)); // ~7 s for the whole stream
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void claudeAtSlowServer(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stop() throws Exception {
        CLAUDE.close();
    }

    @Test
    void ac_t01_9_clientDisconnectClosesUpstreamWithinTwoSecondsAndLogsClientCancelled() throws Exception {
        TestData.insertTenant(jdbc, "acme", 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, "acme", KEY, Set.of(), false);
        String body = """
                {"model":"%s","stream":true,"messages":[{"role":"user","content":"count slowly"}]}""".formatted(MODEL);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        String requestId = null;
        long clientClosedAt;
        // A raw socket, so the test decides exactly when the connection goes away.
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + KEY
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean sawContent = false;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase(Locale.ROOT).startsWith("x-request-id:")) requestId = line.substring(13).trim();
                if (line.startsWith("data: {") && line.contains("\"content\":\"tok1 \"")) {
                    sawContent = true;
                    break;
                }
            }
            assertThat(sawContent).as("two content chunks arrived before the client hung up").isTrue();
            socket.setSoLinger(true, 0); // close with a reset, like a client process that dies
            clientClosedAt = System.nanoTime();
        }

        long upstreamClosedAt = CLAUDE.awaitClientClose(Duration.ofSeconds(5));
        assertThat(upstreamClosedAt).as("gateway closed its upstream connection").isPositive();
        assertThat(Duration.ofNanos(upstreamClosedAt - clientClosedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(CLAUDE.framesWritten()).as("upstream was cut off early").isLessThan(FRAMES.size());

        await().atMost(Duration.ofSeconds(5)).until(() ->
                jdbc.sql("SELECT count(*) FROM request_log").query(Long.class).single() >= 1);
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM request_log").query().listOfRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("status", "client_cancelled")
                .containsEntry("tenant_id", "acme")
                .containsEntry("usage_estimated", true);
        assertThat(row.get("request_id")).isEqualTo(UUID.fromString(requestId));
        assertThat(((Number) row.get("output_tokens")).intValue()).isPositive();
    }
}
