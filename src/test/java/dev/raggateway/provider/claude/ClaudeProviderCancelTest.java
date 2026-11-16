package dev.raggateway.provider.claude;

import com.anthropic.client.AnthropicClient;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.Completion;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.support.SlowSseServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * C23 / AC-t01-9 at the adapter: when the client sink throws {@link ClientDisconnectedException}, the adapter
 * closes the SDK stream and the upstream socket is closed within 2 s, while the upstream still had events to send.
 */
class ClaudeProviderCancelTest {

    @Test
    void sinkDisconnectClosesTheUpstreamSocketWithinTwoSecondsAndRethrowsUnchanged() throws Exception {
        ClaudeSse sse = ClaudeSse.start(ClaudeProviderTest.MODEL, 10).textBlockStart();
        for (int i = 0; i < 30; i++) sse.text("tok" + i + " ");
        sse.blockStop().end("end_turn", 30);
        List<String> frames = sse.frames();

        try (var upstream = new SlowSseServer(frames, Duration.ofMillis(200))) {
            GatewayProperties props = ClaudeProviderTest.properties(upstream.baseUrl());
            AnthropicClient client = ClaudeClientConfig.client(props.providers().get("claude"));
            try {
                var provider = new ClaudeProvider(client, props);
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

                Throwable thrown = catchThrowable(() -> provider.stream(ClaudeProviderTest.request("hi"), sink));
                long thrownAt = System.nanoTime();

                assertThat(thrown).as("rethrown unchanged, never wrapped").isSameAs(disconnect);
                assertThat(received).containsExactly("tok0 ", "tok1 ");
                long closedAt = upstream.awaitClientClose(Duration.ofSeconds(2));
                assertThat(closedAt).as("upstream saw the connection close").isPositive();
                assertThat(Duration.ofNanos(closedAt - thrownAt)).isLessThan(Duration.ofSeconds(2));
                assertThat(upstream.framesWritten()).as("upstream was cut off early").isLessThan(frames.size());
                assertThat(upstream.requests()).isEqualTo(1);
            } finally {
                client.close();
            }
        }
    }
}
