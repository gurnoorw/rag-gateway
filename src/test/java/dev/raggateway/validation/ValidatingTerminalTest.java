package dev.raggateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.cache.CacheFilter;
import dev.raggateway.cache.CacheKeyStrategy;
import dev.raggateway.cache.CacheProperties;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.RequestOutcome;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.cost.CostCalculator;
import dev.raggateway.cost.PriceTable;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.DefaultGatewayPipeline;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.ratelimit.RateLimitFilter;
import dev.raggateway.ratelimit.RateLimitProperties;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import dev.raggateway.resilience.RouterFixture;
import dev.raggateway.support.FakeLlmProvider;
import dev.raggateway.support.FakeLlmProvider.Complete;
import dev.raggateway.support.FakeLlmProvider.Script;
import dev.raggateway.support.FakeLlmProvider.Token;
import dev.raggateway.support.FakeRateLimiter;
import dev.raggateway.support.InMemoryBudgetLedger;
import dev.raggateway.support.InMemoryResponseCache;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.raggateway.support.FakeLlmProvider.failAfterTokens;
import static dev.raggateway.support.FakeLlmProvider.failBeforeFirstToken;
import static dev.raggateway.support.FakeLlmProvider.slowReply;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ValidatingTerminal} over the real router ({@link RouterFixture}) and {@link FakeLlmProvider}s, with the real
 * validator. The cache and billing parts run inside the real pipeline with the cache and rate-limit filters, the way
 * {@code CacheFilterTest} does. HTTP, Redis and the request log are the end-to-end test's job (after t06).
 */
class ValidatingTerminalTest {

    static final String OPUS = "claude-opus-5-5";
    static final String LLAMA = "llama3.2:1b";
    static final Set<String> CATALOG = Set.of(OPUS, LLAMA);
    static final Tenant ACME = new Tenant("acme", "Acme", 1_000_000, BigDecimal.TEN, Set.of());
    static final String SCHEMA = """
            {"type":"object","properties":{"city":{"type":"string"},"temp":{"type":"number"}},
             "required":["city","temp"],"additionalProperties":false}""";
    static final String VALID = "{\"city\":\"Pune\",\"temp\":31.5}";
    /** Invalid against SCHEMA, and full of text that must never reach an error message. */
    static final String INVALID = "{\"city\":\"SECRET-CITY\",\"temp\":\"SECRET-TEMP\",\"SECRET-KEY\":1}";

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final FakeLlmProvider claude = new FakeLlmProvider("claude", Set.of(OPUS));
    final FakeLlmProvider ollama = new FakeLlmProvider("ollama", Set.of(LLAMA));
    final RouterFixture fixture = new RouterFixture(List.of(claude, ollama), List.of(new ChainEntry("ollama", LLAMA)));
    final ValidatingTerminal terminal = new ValidatingTerminal(fixture.router,
            new NetworkntOutputValidator(new ObjectMapper()), new ValidationFailures(meters, CATALOG));

    static ChatRequest request(String schema, boolean stream) {
        return new ChatRequest(OPUS, List.of(new Message(Role.USER, "weather as json")), 64, 0.0, null, List.of(),
                stream, false, schema);
    }

    static RequestContext ctx(ChatRequest request) {
        return new RequestContext(UUID.randomUUID().toString(), System.nanoTime(),
                new ApiKeyPrincipal(UUID.randomUUID().toString(), ACME, Set.of(), false), request);
    }

    /** Tokens split into pieces, then STOP with the given usage. */
    static Script answer(String text, FinishReason finish, int in, int out) {
        List<FakeLlmProvider.Step> steps = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 7) steps.add(new Token(text.substring(i, Math.min(text.length(), i + 7))));
        steps.add(new Complete(new Completion(finish, new Usage(in, out), "upstream-id")));
        return new Script(steps);
    }

    static Script answer(String text, int in, int out) {
        return answer(text, FinishReason.STOP, in, out);
    }

    double failures(String kind) {
        var c = meters.find(MetricNames.VALIDATION_FAILURES).tag("kind", kind).counters();
        return c.stream().mapToDouble(x -> x.count()).sum();
    }

    static List<AttemptOutcome> outcomes(RequestContext ctx) {
        return ctx.attempts().stream().map(Attempt::outcome).toList();
    }

    // ---- schema requested ----

    @Test
    void ac_t07_1_validAnswerIsSentAsOneBurstAfterOneCall() {
        claude.enqueue(answer(VALID, 20, 9));
        var ctx = ctx(request(SCHEMA, true));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(sink.tokens).containsExactly(VALID);
        assertThat(sink.completion.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(outcomes(ctx)).containsExactly(AttemptOutcome.SUCCEEDED);
        assertThat(failures("schema")).isZero();
    }

    @Test
    void ac_t07_2_invalidThenValidIsOneRetryAndTheClientGetsOnlyTheValidAnswer() {
        claude.enqueue(answer(INVALID, 20, 11)).enqueue(answer(VALID, 20, 9));
        var ctx = ctx(request(SCHEMA, false));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(sink.tokens).containsExactly(VALID);
        assertThat(sink.error).isNull();
        assertThat(outcomes(ctx)).containsExactly(AttemptOutcome.INVALID_OUTPUT, AttemptOutcome.SUCCEEDED);
        assertThat(ctx.attempts().get(0).errorKind()).isEqualTo("SCHEMA");
        // AC-t07-8 (unit part): both attempts are billed with the usage the provider reported.
        assertThat(ctx.attempts().get(0).usage()).isEqualTo(new Usage(20, 11));
        assertThat(ctx.billedUsage()).isEqualTo(new Usage(40, 20));
        assertThat(meters.find(MetricNames.VALIDATION_FAILURES).tags("model", OPUS, "kind", "schema").counter().count())
                .isEqualTo(1);
    }

    @Test
    void ac_t07_3_invalidTwiceIs502InvalidModelOutputAfterExactlyTwoCalls() {
        claude.enqueue(answer(INVALID, 20, 11)).enqueue(answer(INVALID, 20, 11));
        var ctx = ctx(request(SCHEMA, true));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(2);
        // AC-t07-4 (unit part): not one content chunk of an invalid answer reaches the client.
        assertThat(sink.tokens).isEmpty();
        assertThat(sink.completion).isNull();
        assertThat(sink.error).isInstanceOfSatisfying(GatewayException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_MODEL_OUTPUT);
            assertThat(e.code().httpStatus()).isEqualTo(502);
        });
        assertThat(outcomes(ctx)).containsExactly(AttemptOutcome.INVALID_OUTPUT, AttemptOutcome.INVALID_OUTPUT);
        assertThat(ctx.billedUsage()).isEqualTo(new Usage(40, 22));
        assertThat(failures("schema")).isEqualTo(2);
    }

    @Test
    void the502MessageNeverQuotesTheOutputAndIsBounded() {
        String manyErrors = "{" + java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> "\"SECRET-" + i + "\":\"SECRET-VALUE-" + i + "\"").collect(Collectors.joining(",")) + "}";
        claude.enqueue(answer(INVALID, 20, 11)).enqueue(answer(manyErrors, 20, 11));
        var sink = new CapturingSink();

        terminal.call(ctx(request(SCHEMA, false)), sink);

        String message = sink.error.getMessage();
        assertThat(message).doesNotContain("SECRET").doesNotContain("Pune");
        assertThat(message).hasSizeLessThanOrEqualTo(300);
        assertThat(message).startsWith("model output failed validation after 2 attempts");
        // At most 3 schema errors are listed, separated by "; ".
        String listed = message.substring(message.indexOf("schema: ") + "schema: ".length());
        assertThat(listed.split("; ")).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void summaryListsAtMostThreeErrors() {
        var verdict = ValidatingTerminal.Verdict.invalid("SCHEMA", List.of("e1", "e2", "e3", "e4", "e5"));
        assertThat(ValidatingTerminal.message(verdict)).endsWith("schema: e1; e2; e3 (+2 more)");
        String longError = "x".repeat(400);
        assertThat(ValidatingTerminal.message(ValidatingTerminal.Verdict.invalid("SCHEMA", List.of(longError))))
                .hasSize(300).endsWith("...");
    }

    @Test
    void ac_t07_9_truncatedAnswerIsInvalidEvenWhenItsPrefixParses() {
        claude.enqueue(answer(VALID, FinishReason.LENGTH, 20, 64)).enqueue(answer(VALID, FinishReason.LENGTH, 20, 64));
        var ctx = ctx(request(SCHEMA, false));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(sink.error).isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_MODEL_OUTPUT));
        assertThat(sink.error.getMessage()).contains("max_tokens");
        assertThat(ctx.attempts()).extracting(Attempt::errorKind).containsExactly("TRUNCATED", "TRUNCATED");
    }

    @Test
    void truncatedThenValidIsServed() {
        claude.enqueue(answer("{\"city\":\"Pu", FinishReason.LENGTH, 20, 64)).enqueue(answer(VALID, 20, 9));
        var sink = new CapturingSink();

        terminal.call(ctx(request(SCHEMA, false)), sink);

        assertThat(sink.tokens).containsExactly(VALID);
    }

    @Test
    void refusalIsInvalidWithASchema() {
        claude.enqueue(answer("", FinishReason.CONTENT_FILTER, 20, 0))
                .enqueue(answer(VALID, FinishReason.CONTENT_FILTER, 20, 3));
        var ctx = ctx(request(SCHEMA, false));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(sink.error).isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_MODEL_OUTPUT));
        assertThat(ctx.attempts()).extracting(Attempt::errorKind).containsExactly("REFUSED", "REFUSED");
    }

    @Test
    void aProviderErrorIsPassedThroughWithoutAValidationRetry() {
        claude.enqueue(failBeforeFirstToken(400));
        var ctx = ctx(request(SCHEMA, false));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(1);
        assertThat(sink.error).isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_REJECTED));
        assertThat(failures("schema")).isZero();
    }

    @Test
    void aRetryableFailureBeforeTheFirstTokenIsStillRetriedByTheRouter() {
        claude.enqueue(failBeforeFirstToken(503)).enqueue(answer(VALID, 20, 9));
        var sink = new CapturingSink();

        terminal.call(ctx(request(SCHEMA, false)), sink);

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(sink.tokens).containsExactly(VALID);
    }

    /** D-43 known limit: the router counts buffered tokens as sent, so a mid-call failure is not retried. */
    @Test
    void knownLimit_aFailureInTheMiddleOfABufferedCallIsA502AtOnce() {
        claude.enqueue(failAfterTokens("{\"city\":")).enqueue(answer(VALID, 20, 9));
        var ctx = ctx(request(SCHEMA, true));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(1);
        assertThat(ollama.calls()).isZero();
        assertThat(sink.tokens).isEmpty();
        assertThat(sink.error).isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_ERROR));
    }

    @Test
    void aValidAnswerFromTheFallbackProviderIsServed() {
        claude.enqueue(failBeforeFirstToken(503)).enqueue(failBeforeFirstToken(503));
        ollama.enqueue(answer(VALID, 20, 9));
        var ctx = ctx(request(SCHEMA, false));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(sink.tokens).containsExactly(VALID);
        assertThat(ctx.servingAttempt().orElseThrow().provider()).isEqualTo("ollama");
    }

    @Test
    void aClientThatLeavesDuringTheBurstPropagatesTheDisconnect() {
        claude.enqueue(answer(VALID, 20, 9));
        var ctx = ctx(request(SCHEMA, true));
        var sink = new CapturingSink().disconnectOnToken();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> terminal.call(ctx, sink))
                .isInstanceOf(ClientDisconnectedException.class);
        assertThat(sink.completion).isNull();
    }

    // ---- no schema ----

    @Test
    void ac_t07_5_withoutASchemaTokensStreamAsTheyArrive() {
        claude.enqueue(slowReply(Duration.ofMillis(100), "a", "b", "c", "d"));
        var ctx = ctx(request(null, true));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(sink.tokens).containsExactly("a", "b", "c", "d");
        // Arrival times follow the provider's gaps; a buffer would deliver all four at the same instant.
        long spreadMillis = (sink.arrivalNanos.get(3) - sink.arrivalNanos.get(0)) / 1_000_000;
        assertThat(spreadMillis).isGreaterThanOrEqualTo(250);
    }

    @Test
    void withoutASchemaAnythingGoesAndNothingIsValidated() {
        claude.enqueue(answer("plain prose, not JSON", FinishReason.LENGTH, 20, 64));
        var ctx = ctx(request(null, false));
        var sink = new CapturingSink();

        terminal.call(ctx, sink);

        assertThat(claude.calls()).isEqualTo(1);
        assertThat(String.join("", sink.tokens)).isEqualTo("plain prose, not JSON");
        assertThat(sink.completion.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(outcomes(ctx)).containsExactly(AttemptOutcome.SUCCEEDED);
    }

    // ---- labels ----

    @Test
    void failureCounterLabelsAreBounded() {
        var failures = new ValidationFailures(meters, CATALOG);
        failures.increment(OPUS, ValidationFailures.SCHEMA);
        failures.increment("client-chosen-" + UUID.randomUUID(), ValidationFailures.STRUCTURAL);

        List<Meter> series = meters.find(MetricNames.VALIDATION_FAILURES).meters().stream().toList();
        assertThat(series).allSatisfy(m -> {
            assertThat(m.getId().getTags()).extracting(t -> t.getKey()).containsExactlyInAnyOrder("model", "kind")
                    .allMatch(MetricNames.ALLOWED_LABELS::contains);
            assertThat(m.getId().getTag("model")).isIn(OPUS, LLAMA, MetricNames.MODEL_OTHER);
            assertThat(m.getId().getTag("kind")).isIn("schema", "structural");
        });
    }

    // ---- inside the pipeline: cache and billing (AC-t07-7, AC-t07-8 unit parts) ----

    final InMemoryResponseCache store = new InMemoryResponseCache();
    static final CacheKeyStrategy KEYS = (tenant, r) -> "test:" + tenant + ":" + r.model() + ":" + r.messages();
    final FakeRateLimiter limiter = new FakeRateLimiter();
    final InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();

    DefaultGatewayPipeline pipeline() {
        var costs = new CostCalculator(new PriceTable(Map.of(
                OPUS, new PriceTable.Price(new BigDecimal("4.00"), new BigDecimal("20.00")),
                LLAMA, new PriceTable.Price(BigDecimal.ZERO, BigDecimal.ZERO))));
        var rateLimit = new RateLimitFilter(limiter, ledger, new CharRatioTokenEstimator(), costs,
                new RateLimitProperties(Duration.ofMinutes(1)), meters);
        var cache = new CacheFilter(store, KEYS, CacheProperties.defaults(), meters);
        List<GatewayFilter> filters = List.of(rateLimit, cache);
        return new DefaultGatewayPipeline(filters, terminal, List.of(), meters);
    }

    @Test
    void ac_t07_7_invalidTwiceLeavesNoCacheEntry() {
        claude.enqueue(answer(INVALID, 20, 11)).enqueue(answer(INVALID, 20, 11));
        var ctx = ctx(request(SCHEMA, false));

        pipeline().run(ctx, new CapturingSink());

        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.ERROR);
        assertThat(ctx.errorCode()).isEqualTo(ErrorCode.INVALID_MODEL_OUTPUT);
        assertThat(store.size()).isZero();
        assertThat(store.puts()).isZero();
    }

    @Test
    void invalidThenValidCachesOnlyTheValidAnswer() {
        claude.enqueue(answer(INVALID, 20, 11)).enqueue(answer(VALID, 20, 9));
        var request = request(SCHEMA, false);
        var ctx = ctx(request);

        pipeline().run(ctx, new CapturingSink());

        assertThat(ctx.outcome()).isEqualTo(RequestOutcome.SUCCESS);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.get(KEYS.keyFor(ACME.id(), request)).orElseThrow().content()).isEqualTo(VALID);

        // The replay serves the valid answer without a provider call.
        var replay = new CapturingSink();
        pipeline().run(ctx(request), replay);
        assertThat(replay.tokens).containsExactly(VALID);
        assertThat(claude.calls()).isEqualTo(2);
    }

    @Test
    void ac_t07_8_bothAttemptsAreChargedToTheBudget() {
        claude.enqueue(answer(INVALID, 20, 11)).enqueue(answer(VALID, 20, 9));
        var ctx = ctx(request(SCHEMA, false));

        pipeline().run(ctx, new CapturingSink());

        // Settlement and the log row read billedUsage() (D-33): the invalid attempt is in it.
        assertThat(ctx.billedUsage()).isEqualTo(new Usage(40, 20));
        assertThat(ctx.attempts()).hasSize(2);
        // Opus at $4 / $20 per million: 40 in = 160 micro-USD, 20 out = 400 micro-USD.
        assertThat(ledger.spentMicroUsd(ACME.id(), YearMonth.now(ZoneOffset.UTC))).isEqualTo(560);
        assertThat(failures("schema")).isEqualTo(1);
    }

    // ---- sink ----

    static final class CapturingSink implements StreamSink {
        final List<String> tokens = new ArrayList<>();
        final List<Long> arrivalNanos = new ArrayList<>();
        Completion completion;
        Throwable error;
        private boolean disconnectOnToken;

        CapturingSink disconnectOnToken() {
            this.disconnectOnToken = true;
            return this;
        }

        @Override
        public void onToken(String text) {
            if (disconnectOnToken) throw new ClientDisconnectedException(new IOException("broken pipe"));
            tokens.add(text);
            arrivalNanos.add(System.nanoTime());
        }

        @Override
        public void onComplete(Completion c) {
            completion = c;
        }

        @Override
        public void onError(Throwable e) {
            error = e;
        }
    }
}
