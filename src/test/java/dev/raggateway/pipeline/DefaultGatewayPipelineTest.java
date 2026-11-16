package dev.raggateway.pipeline;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.resilience.RouterFixture;
import dev.raggateway.support.FakeLlmProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DefaultGatewayPipelineTest {

    private static final String MODEL = "claude-opus-5-5";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeLlmProvider claude = new FakeLlmProvider("claude", Set.of(MODEL));
    private final Terminal terminal = RouterFixture.single(claude);

    static RequestContext ctx(boolean stream) {
        var tenant = new Tenant("t1", "Tenant 1", 10_000, BigDecimal.TEN, Set.of());
        var caller = new ApiKeyPrincipal(UUID.randomUUID().toString(), tenant, Set.of(), false);
        var request = new ChatRequest(MODEL, List.of(new Message(Role.USER, "hello there")), 64,
                null, null, List.of(), stream, false, null);
        return new RequestContext(UUID.randomUUID().toString(), System.nanoTime(), caller, request);
    }

    /** Client sink like the edge's: marks the first token, and fails writes after {@code failAfter} tokens. */
    static class ClientSink implements StreamSink {
        final RequestContext ctx;
        final int failAfter;
        final List<String> events = new ArrayList<>();
        int tokens;

        ClientSink(RequestContext ctx, int failAfter) {
            this.ctx = ctx;
            this.failAfter = failAfter;
        }

        ClientSink(RequestContext ctx) {
            this(ctx, Integer.MAX_VALUE);
        }

        @Override
        public void onToken(String text) {
            if (tokens >= failAfter) throw new ClientDisconnectedException(new IOException("Broken pipe"));
            tokens++;
            ctx.markFirstTokenSent(System.nanoTime());
            events.add("token:" + text);
        }

        @Override
        public void onComplete(Completion completion) {
            events.add("complete");
        }

        @Override
        public void onError(Throwable error) {
            events.add("error:" + (error instanceof GatewayException g ? g.code().code() : error.getClass().getSimpleName()));
        }
    }

    static final class CountingListener implements RequestCompletionListener {
        final AtomicInteger calls = new AtomicInteger();
        RequestOutcome seenOutcome;

        @Override
        public void onRequestFinished(RequestContext ctx) {
            calls.incrementAndGet();
            seenOutcome = ctx.outcome();
        }
    }

    static final class FailingListener implements RequestCompletionListener {
        @Override
        public void onRequestFinished(RequestContext ctx) {
            throw new IllegalStateException("listener boom");
        }
    }

    private DefaultGatewayPipeline pipeline(List<GatewayFilter> filters, RequestCompletionListener... listeners) {
        return new DefaultGatewayPipeline(filters, terminal, List.of(listeners), registry);
    }

    @Test
    void successStreamsTokensCompletesAndFinishesAsSuccess() {
        claude.enqueue(FakeLlmProvider.reply("Hel", "lo"));
        var listener = new CountingListener();
        RequestContext ctx = ctx(true);
        var sink = new ClientSink(ctx);

        pipeline(List.of(), listener).run(ctx, sink);

        assertThat(sink.events).containsExactly("token:Hel", "token:lo", "complete");
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.SUCCESS);
        assertThat(ctx.httpStatus()).isEqualTo(200);
        assertThat(ctx.errorCode()).isNull();
        assertThat(ctx.attempts()).singleElement()
                .satisfies(a -> assertThat(a.outcome()).isEqualTo(AttemptOutcome.SUCCEEDED));
        assertThat(listener.calls).hasValue(1);
        assertThat(listener.seenOutcome).as("finish() runs before listeners").isEqualTo(RequestOutcome.SUCCESS);
    }

    @Test
    void filterExceptionGoesToOnErrorAndTheProviderIsNotCalled() {
        GatewayFilter rejecting = (c, s, chain) -> {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "max_tokens above cap");
        };
        var listener = new CountingListener();
        RequestContext ctx = ctx(true);
        var sink = new ClientSink(ctx);

        assertThatCode(() -> pipeline(List.of(rejecting), listener).run(ctx, sink)).doesNotThrowAnyException();

        assertThat(sink.events).containsExactly("error:invalid_request");
        assertThat(claude.calls()).isZero();
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(ctx.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ctx.httpStatus()).isEqualTo(400);
        assertThat(listener.calls).hasValue(1);
    }

    @Test
    void unexpectedFilterExceptionBecomesInternalError() {
        GatewayFilter buggy = (c, s, chain) -> {
            throw new NullPointerException("bug");
        };
        RequestContext ctx = ctx(false);
        var sink = new ClientSink(ctx);

        pipeline(List.of(buggy)).run(ctx, sink);

        assertThat(sink.events).containsExactly("error:internal_error");
        assertThat(ctx.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ctx.httpStatus()).isEqualTo(500);
    }

    @Test
    void clientDisconnectEndsAsCancelledWithEstimatedUsage() {
        claude.enqueue(FakeLlmProvider.reply("one", "two", "three"));
        var listener = new CountingListener();
        RequestContext ctx = ctx(true);
        var sink = new ClientSink(ctx, 1);

        pipeline(List.of(), listener).run(ctx, sink);

        assertThat(sink.events).containsExactly("token:one");
        assertThat(claude.cancellations()).isEqualTo(1);
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.CLIENT_CANCELLED);
        assertThat(ctx.httpStatus()).isEqualTo(200);
        assertThat(ctx.attempts()).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(AttemptOutcome.CLIENT_CANCELLED);
            assertThat(a.usageEstimated()).isTrue();
            assertThat(a.usage().inputTokens()).isPositive();
            assertThat(a.usage().outputTokens()).isPositive();
        });
        assertThat(listener.calls).hasValue(1);
    }

    @Test
    void disconnectBeforeAnyByteRecords499() {
        claude.enqueue(FakeLlmProvider.reply("one"));
        RequestContext ctx = ctx(true);

        pipeline(List.of()).run(ctx, new ClientSink(ctx, 0));

        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.CLIENT_CANCELLED);
        assertThat(ctx.httpStatus()).isEqualTo(DefaultGatewayPipeline.CLIENT_CLOSED_REQUEST);
    }

    @Test
    void providerFailureBeforeFirstTokenIsAnErrorStatus() {
        // The terminal is the t03 router: a retryable failure on the only provider, twice, is a 503.
        claude.defaultScript(FakeLlmProvider.failBeforeFirstToken(503));
        RequestContext ctx = ctx(true);
        var sink = new ClientSink(ctx);

        pipeline(List.of()).run(ctx, sink);

        assertThat(sink.events).containsExactly("error:all_providers_failed");
        assertThat(ctx.httpStatus()).isEqualTo(503);
        assertThat(ctx.errorCode()).isEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
    }

    @Test
    void providerFailureAfterFirstTokenOfAStreamRecords200() {
        claude.enqueue(FakeLlmProvider.failAfterTokens("partial"));
        RequestContext ctx = ctx(true);
        var sink = new ClientSink(ctx);

        pipeline(List.of()).run(ctx, sink);

        assertThat(sink.events).containsExactly("token:partial", "error:provider_error");
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(ctx.httpStatus()).as("client saw 200 and an SSE error event").isEqualTo(200);
    }

    @Test
    void listenersRunOnceEachAndAFailingListenerIsCountedWithoutStoppingOthers() {
        var before = new CountingListener();
        var after = new CountingListener();
        RequestContext ctx = ctx(false);

        assertThatCode(() -> pipeline(List.of(), before, new FailingListener(), after).run(ctx, new ClientSink(ctx)))
                .doesNotThrowAnyException();

        assertThat(before.calls).hasValue(1);
        assertThat(after.calls).hasValue(1);
        assertThat(registry.counter(MetricNames.LISTENER_FAILURES, "listener", "FailingListener").count())
                .isEqualTo(1);
    }

    @Order(FilterOrder.MODEL_SELECTION)
    static final class Second implements GatewayFilter {
        final List<String> trace;

        Second(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) {
            trace.add("model-selection");
            chain.next(ctx, sink);
        }
    }

    @Order(FilterOrder.REQUEST_VALIDATION)
    static final class First implements GatewayFilter {
        final List<String> trace;

        First(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) {
            trace.add("request-validation");
            chain.next(ctx, sink);
        }
    }

    @Test
    void filtersAreSortedByOrderAnnotation() {
        List<String> trace = new ArrayList<>();
        RequestContext ctx = ctx(false);

        pipeline(List.of(new Second(trace), new First(trace))).run(ctx, new ClientSink(ctx));

        assertThat(trace).containsExactly("request-validation", "model-selection");
    }

    @Test
    void filterThatReturnsWithoutTerminalEventProducesInternalError() {
        GatewayFilter swallowing = (c, s, chain) -> { };
        RequestContext ctx = ctx(false);
        var sink = new ClientSink(ctx);

        pipeline(List.of(swallowing)).run(ctx, sink);

        assertThat(sink.events).containsExactly("error:internal_error");
        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
    }
}
