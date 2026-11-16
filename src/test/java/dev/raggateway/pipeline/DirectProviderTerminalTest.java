package dev.raggateway.pipeline;

import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.support.FakeLlmProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectProviderTerminalTest {

    private final FakeLlmProvider claude = new FakeLlmProvider("claude", Set.of("claude-opus-5-5"));
    private final DirectProviderTerminal terminal =
            new DirectProviderTerminal(List.of(claude), new CharRatioTokenEstimator());

    static final class RecordingSink implements StreamSink {
        final List<Object> events = new ArrayList<>();

        @Override
        public void onToken(String text) {
            events.add(text);
        }

        @Override
        public void onComplete(Completion completion) {
            events.add(completion);
        }

        @Override
        public void onError(Throwable error) {
            events.add(error);
        }
    }

    @Test
    void successRecordsOneAttemptWithProviderUsage() {
        RequestContext ctx = DefaultGatewayPipelineTest.ctx(true);
        var sink = new RecordingSink();
        claude.enqueue(FakeLlmProvider.reply("a", "b"));

        terminal.call(ctx, sink);

        assertThat(sink.events).hasSize(3).last().isInstanceOf(Completion.class);
        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.provider()).isEqualTo("claude");
            assertThat(a.model()).isEqualTo("claude-opus-5-5");
            assertThat(a.fallback()).isFalse();
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
            assertThat(a.usage()).isEqualTo(new Usage(10, 2));
            assertThat(a.usageEstimated()).isFalse();
            assertThat(a.firstTokenNanos()).isNotNegative();
            assertThat(a.errorKind()).isNull();
        });
    }

    @Test
    void nonRetryableUpstream4xxIsUpstreamRejected() {
        RequestContext ctx = DefaultGatewayPipelineTest.ctx(false);
        var sink = new RecordingSink();
        claude.enqueue(FakeLlmProvider.failBeforeFirstToken(400));

        terminal.call(ctx, sink);

        assertThat(sink.events).singleElement().isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_REJECTED));
        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN);
            assertThat(a.usageEstimated()).isTrue();
            assertThat(a.usage().outputTokens()).isZero();
            assertThat(a.firstTokenNanos()).isEqualTo(-1);
            assertThat(a.errorKind()).isEqualTo("HTTP_STATUS");
        });
    }

    @Test
    void retryable429And5xxAreProviderError() {
        for (int status : new int[]{429, 500, 529}) {
            RequestContext ctx = DefaultGatewayPipelineTest.ctx(false);
            var sink = new RecordingSink();
            claude.enqueue(FakeLlmProvider.failBeforeFirstToken(status));

            terminal.call(ctx, sink);

            assertThat(sink.events).singleElement().isInstanceOfSatisfying(GatewayException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_ERROR));
        }
    }

    @Test
    void connectionFailureMidStreamIsRecordedAsMidStreamWithEstimatedOutput() {
        RequestContext ctx = DefaultGatewayPipelineTest.ctx(true);
        var sink = new RecordingSink();
        claude.enqueue(FakeLlmProvider.failAfterTokens("abcdef"));

        terminal.call(ctx, sink);

        assertThat(sink.events).first().isEqualTo("abcdef");
        assertThat(sink.events).last().isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_ERROR));
        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.FAILED_MID_STREAM);
            assertThat(a.usage().outputTokens()).isEqualTo(2);
            assertThat(a.errorKind()).isEqualTo("CONNECTION");
        });
    }

    @Test
    void disconnectRecordsCancelledAttemptAndRethrowsWithoutTerminalEvent() {
        RequestContext ctx = DefaultGatewayPipelineTest.ctx(true);
        var sink = new DefaultGatewayPipelineTest.ClientSink(ctx, 1);
        claude.enqueue(FakeLlmProvider.reply("abc", "def"));

        assertThatThrownBy(() -> terminal.call(ctx, sink)).isInstanceOf(ClientDisconnectedException.class);

        assertThat(sink.events).containsExactly("token:abc");
        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.CLIENT_CANCELLED);
            assertThat(a.usageEstimated()).isTrue();
            // input estimate + both pieces the provider produced ("abc", "def": 1 token each)
            assertThat(a.usage().outputTokens()).isEqualTo(2);
        });
    }

    @Test
    void disconnectOnTheFinalWriteKeepsProviderUsage() {
        RequestContext ctx = DefaultGatewayPipelineTest.ctx(true);
        StreamSink sink = new DefaultGatewayPipelineTest.ClientSink(ctx) {
            @Override
            public void onComplete(Completion completion) {
                throw new ClientDisconnectedException(new java.io.IOException("Broken pipe"));
            }
        };
        claude.enqueue(FakeLlmProvider.script(new FakeLlmProvider.Token("x"),
                new FakeLlmProvider.Complete(new Completion(FinishReason.STOP, new Usage(11, 22), "m"))));

        assertThatThrownBy(() -> terminal.call(ctx, sink)).isInstanceOf(ClientDisconnectedException.class);

        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.CLIENT_CANCELLED);
            assertThat(a.usage()).isEqualTo(new Usage(11, 22));
            assertThat(a.usageEstimated()).isFalse();
        });
    }

    @Test
    void modelWithoutProviderIsInvalidRequestAndNoAttempt() {
        var request = DefaultGatewayPipelineTest.ctx(false);
        request.setRequest(request.request().withModel("llama3.2:1b"));
        var sink = new RecordingSink();

        terminal.call(request, sink);

        assertThat(sink.events).singleElement().isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(request.attempts()).isEmpty();
        assertThat(claude.calls()).isZero();
    }

    @Test
    void upstreamMessageOfGatewayExceptionCarriesNoProviderBody() {
        var e = DirectProviderTerminal.toGatewayException(claude,
                new ProviderException(ProviderException.Kind.HTTP_STATUS, "upstream said: secret prompt", false, 400,
                        null, null));
        assertThat(e.getMessage()).isEqualTo("provider claude rejected the request (HTTP 400)");
    }
}
