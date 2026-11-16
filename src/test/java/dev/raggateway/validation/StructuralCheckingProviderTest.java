package dev.raggateway.validation;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import dev.raggateway.resilience.RouterFixture;
import dev.raggateway.support.FakeLlmProvider;
import dev.raggateway.support.FakeLlmProvider.Await;
import dev.raggateway.support.FakeLlmProvider.Complete;
import dev.raggateway.support.FakeLlmProvider.Script;
import dev.raggateway.support.FakeLlmProvider.Token;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.raggateway.support.FakeLlmProvider.failBeforeFirstToken;
import static dev.raggateway.support.FakeLlmProvider.reply;
import static dev.raggateway.support.FakeLlmProvider.script;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuralCheckingProviderTest {

    static final String OPUS = "claude-opus-5-5";
    static final String LLAMA = "llama3.2:1b";

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ValidationFailures failures = new ValidationFailures(meters, Set.of(OPUS, LLAMA));
    final FakeLlmProvider claude = new FakeLlmProvider("claude", Set.of(OPUS));
    final StructuralCheckingProvider checked = new StructuralCheckingProvider(claude, failures);

    static ChatRequest request(String model) {
        return new ChatRequest(model, List.of(new Message(Role.USER, "hello there")), 64, null, null, List.of(), true,
                false, null);
    }

    static Script empty(FinishReason finish) {
        return script(new Complete(new Completion(finish, new Usage(12, 0), "upstream-id")));
    }

    double structuralFailures(String model) {
        var c = meters.find(MetricNames.VALIDATION_FAILURES).tags("model", model, "kind", "structural").counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void emptyAnswerWithStopIsARetryableMalformedResponse() {
        claude.enqueue(empty(FinishReason.STOP));
        var sink = new Events();

        assertThatThrownBy(() -> checked.stream(request(OPUS), sink))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderException.Kind.MALFORMED_RESPONSE);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.fallbackAllowed()).isTrue();
                });
        assertThat(sink.events).isEmpty(); // onComplete held back, never forwarded
        assertThat(structuralFailures(OPUS)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = FinishReason.class, names = {"LENGTH", "CONTENT_FILTER"})
    void emptyAnswerWithLengthOrContentFilterIsLegitimate(FinishReason finish) throws Exception {
        claude.enqueue(empty(finish));
        var sink = new Events();

        checked.stream(request(OPUS), sink);

        assertThat(sink.events).containsExactly("complete:" + finish);
        assertThat(structuralFailures(OPUS)).isZero();
    }

    @Test
    void anAnswerWithTextPassesAndCompletesAfterTheTokens() throws Exception {
        claude.enqueue(reply("Hel", "lo"));
        var sink = new Events();

        checked.stream(request(OPUS), sink);

        assertThat(sink.events).containsExactly("token:Hel", "token:lo", "complete:STOP");
    }

    /** Tokens are forwarded as they come, not held until the end (AC-t07-5 relies on it). */
    @Test
    void tokensAreForwardedImmediately() throws Exception {
        var release = new CountDownLatch(1);
        claude.enqueue(script(new Token("first"), new Await(release),
                new Complete(new Completion(FinishReason.STOP, new Usage(1, 1), "m"))));
        var firstSeen = new CountDownLatch(1);
        var sink = new Events() {
            @Override
            public void onToken(String text) {
                super.onToken(text);
                firstSeen.countDown();
            }
        };

        var call = CompletableFuture.runAsync(() -> {
            try {
                checked.stream(request(OPUS), sink);
            } catch (ProviderException e) {
                throw new IllegalStateException(e);
            }
        });
        // The adapter is still blocked on the latch while the sink already has the token.
        assertThat(firstSeen.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(call).isNotDone();
        release.countDown();
        call.get(5, TimeUnit.SECONDS);
        assertThat(sink.events).containsExactly("token:first", "complete:STOP");
    }

    @Test
    void adapterFailuresAndDisconnectsPropagateUnchanged() {
        claude.enqueue(failBeforeFirstToken(500));
        assertThatThrownBy(() -> checked.stream(request(OPUS), new Events()))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.httpStatus()).isEqualTo(500));

        claude.enqueue(reply("x"));
        var gone = new Events() {
            @Override
            public void onToken(String text) {
                throw new ClientDisconnectedException(new IOException("broken pipe"));
            }
        };
        assertThatThrownBy(() -> checked.stream(request(OPUS), gone)).isInstanceOf(ClientDisconnectedException.class);
        assertThat(claude.cancellations()).isEqualTo(1);
        assertThat(structuralFailures(OPUS)).isZero();
    }

    @Test
    void nameAndSupportsAreTheAdapters() {
        assertThat(checked.name()).isEqualTo("claude");
        assertThat(checked.supports(OPUS)).isTrue();
        assertThat(checked.supports(LLAMA)).isFalse();
        assertThat(checked.delegate()).isSameAs(claude);
    }

    // ---- inside the router: retried and fallen back like any failure before the first token ----

    static RequestContext ctx(ChatRequest request) {
        var tenant = new Tenant("acme", "Acme", 1_000_000, BigDecimal.TEN, Set.of());
        return new RequestContext(UUID.randomUUID().toString(), System.nanoTime(),
                new ApiKeyPrincipal("k", tenant, Set.of(), false), request);
    }

    @Test
    void anEmptyStopIsRetriedOnTheSameProvider() {
        claude.enqueue(empty(FinishReason.STOP)).enqueue(reply("fine"));
        var router = new RouterFixture(List.of(checked), List.of()).router;
        var ctx = ctx(request(OPUS));
        var sink = new Events();

        router.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(sink.events).containsExactly("token:fine", "complete:STOP");
        Attempt first = ctx.attempts().get(0);
        assertThat(first.outcome()).isEqualTo(AttemptOutcome.FAILED_BEFORE_FIRST_TOKEN);
        assertThat(first.errorKind()).isEqualTo("MALFORMED_RESPONSE");
        // Billed with the usage the provider reported for the empty answer, not an estimate (D-44).
        assertThat(first.usageEstimated()).isFalse();
        assertThat(first.usage()).isEqualTo(new Usage(12, 0));
        assertThat(ctx.attempts().get(1).outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
    }

    @Test
    void anEmptyAnswerAfterLongThinkingIsBilledForItsReportedOutputTokens() {
        // A thinking model can spend many output tokens and still end with no text (D-44).
        claude.enqueue(script(new Complete(new Completion(FinishReason.STOP, new Usage(40, 900), "upstream-id"))))
                .enqueue(reply("fine"));
        var router = new RouterFixture(List.of(checked), List.of()).router;
        var ctx = ctx(request(OPUS));

        router.call(ctx, new Events());

        assertThat(ctx.attempts().get(0).usage()).isEqualTo(new Usage(40, 900));
        assertThat(ctx.billedUsage().outputTokens()).isGreaterThanOrEqualTo(900);
    }

    @Test
    void emptyStopsOnThePrimaryFallBackToTheNextProvider() {
        var ollama = new FakeLlmProvider("ollama", Set.of(LLAMA)).enqueue(reply("from llama"));
        claude.defaultScript(empty(FinishReason.STOP));
        var fixture = new RouterFixture(List.of(checked, new StructuralCheckingProvider(ollama, failures)),
                List.of(new ChainEntry("ollama", LLAMA)));
        var ctx = ctx(request(OPUS));
        var sink = new Events();

        fixture.router.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(ollama.calls()).isEqualTo(1);
        assertThat(sink.events).containsExactly("token:from llama", "complete:STOP");
        assertThat(structuralFailures(OPUS)).isEqualTo(2);
        assertThat(ctx.servingAttempt().orElseThrow().fallback()).isTrue();
    }

    static class Events implements StreamSink {
        final List<String> events = new ArrayList<>();

        @Override
        public void onToken(String text) {
            events.add("token:" + text);
        }

        @Override
        public void onComplete(Completion completion) {
            events.add("complete:" + completion.finishReason());
        }

        @Override
        public void onError(Throwable error) {
            events.add("error:" + error.getMessage());
        }
    }
}
