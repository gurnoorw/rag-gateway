package dev.raggateway.rag.query;

import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.error.RateLimitedException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.GatewayPipeline;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link RagService} with a {@link FakeRetriever} and a scripted pipeline: the request, accounting and outcomes. */
class RagServiceTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final AtomicLong clock = new AtomicLong(1_000);
    final FakeRetriever retriever = new FakeRetriever() {
        @Override
        public dev.raggateway.rag.RetrievalResult retrieve(RetrievalQuery query) {
            clock.addAndGet(7_000_000); // retrieval "takes" 7 ms on the test clock
            return super.retrieve(query);
        }
    };
    final List<RequestContext> contexts = new ArrayList<>();
    BiConsumer<RequestContext, StreamSink> script = (ctx, sink) -> {
        ctx.startAttempt("claude", "claude-opus-5-5", false);
        sink.onToken("Pods restart [S2]");
        sink.onToken(" and [S9].");
        sink.onComplete(new Completion(FinishReason.STOP, new Usage(2100, 12), "claude-opus-5-5"));
    };
    final GatewayPipeline pipeline = (ctx, sink) -> {
        contexts.add(ctx);
        script.accept(ctx, sink);
    };
    final RagService service = new RagService(retriever, pipeline, RagProperties.Answer.defaults(), registry,
            clock::get);

    static final Tenant TENANT = new Tenant("acme", "Acme", 1_000_000, BigDecimal.TEN, Set.of());
    static final ApiKeyPrincipal CALLER = new ApiKeyPrincipal("key-1", TENANT, Set.of("eng", "ops"), false);

    static RagService.Question question(String text, RetrievalMode mode) {
        return new RagService.Question("k8s", text, 5, mode, "req-1", 42L, false);
    }

    double queries(String result) {
        var c = registry.find(MetricNames.RAG_QUERIES).tags("tenant", "acme", "result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void theAnswerRequestIsNonStreamingAutoTemperatureZeroWithTheConfiguredMaxTokens() {
        retriever.returning(FakeRetriever.chunks(3));

        service.answer(CALLER, question("Why do pods restart?", RetrievalMode.HYBRID));

        assertThat(contexts).hasSize(1);
        ChatRequest request = contexts.get(0).request();
        assertThat(request.model()).isEqualTo(ChatRequest.AUTO);
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.maxTokens()).isEqualTo(512);
        assertThat(request.stream()).isFalse();
        assertThat(request.hasSchema()).isFalse();
        assertThat(request.messages()).extracting(m -> m.role()).containsExactly(Role.SYSTEM, Role.USER);
        assertThat(request.messages())
                .isEqualTo(RagPromptBuilder.messages(FakeRetriever.chunks(3), "Why do pods restart?"));
    }

    @Test
    void theContextIsCreatedAfterRetrievalWithTheRequestStartAndRetrievalAsAWait() {
        retriever.returning(FakeRetriever.chunks(2));

        service.answer(CALLER, new RagService.Question("k8s", "q", 5, RetrievalMode.HYBRID, "req-7", 42L, true));

        RequestContext ctx = contexts.get(0);
        assertThat(ctx.requestId()).isEqualTo("req-7");
        assertThat(ctx.startNanos()).isEqualTo(42L);
        assertThat(ctx.waitNanos()).isEqualTo(7_000_000L);
        assertThat(ctx.caller()).isSameAs(CALLER);
        assertThat(ctx.cacheBypassRequested()).isTrue();
        assertThat(retriever.queries()).hasSize(1);
    }

    @Test
    void retrievalUsesTheKeysTenantAndGroups() {
        retriever.returning(FakeRetriever.chunks(1));

        service.answer(CALLER, new RagService.Question("k8s", "q", 3, RetrievalMode.KEYWORD, "r", 0, false));

        RetrievalQuery q = retriever.queries().get(0);
        assertThat(q.tenantId()).isEqualTo("acme");
        assertThat(q.groups()).containsExactlyInAnyOrder("eng", "ops");
        assertThat(q.collection()).isEqualTo("k8s");
        assertThat(q.topK()).isEqualTo(3);
        assertThat(q.mode()).isEqualTo(RetrievalMode.KEYWORD);
    }

    @Test
    void anAnsweredQueryCitesOnlyPromptLabelsAndListsEverySource() {
        retriever.returning(FakeRetriever.chunks(5));

        RagService.Result result = service.answer(CALLER, question("q", RetrievalMode.HYBRID));

        RagAnswer body = result.body();
        assertThat(body.answer()).isEqualTo("Pods restart [S2] and [S9].");
        assertThat(body.citations()).extracting(RagAnswer.Citation::source).containsExactly("S2");
        assertThat(body.citations().get(0).chunkId()).isEqualTo("chunk-2");
        assertThat(body.sources()).extracting(RagAnswer.Source::chunkId)
                .containsExactly("chunk-1", "chunk-2", "chunk-3", "chunk-4", "chunk-5");
        assertThat(body.noAnswer()).isFalse();
        assertThat(body.retrievalMode()).isEqualTo("hybrid");
        assertThat(body.model()).isEqualTo("claude-opus-5-5");
        assertThat(body.usage().promptTokens()).isEqualTo(2100);
        assertThat(body.usage().completionTokens()).isEqualTo(12);
        assertThat(body.requestId()).isEqualTo("req-1");
        assertThat(result.headers()).containsEntry(GatewayHeaders.PROVIDER, "claude")
                .containsEntry(GatewayHeaders.MODEL, "claude-opus-5-5")
                .containsEntry(GatewayHeaders.CACHE, "NOT_ELIGIBLE");
        assertThat(queries(RagService.ANSWERED)).isEqualTo(1.0);
    }

    @Test
    void aCacheHitIsServedByTheCache() {
        retriever.returning(FakeRetriever.chunks(1));
        script = (ctx, sink) -> {
            ctx.setRequest(ctx.request().withModel("claude-haiku-5-5"));
            ctx.setRouteRule("short");
            ctx.setCacheStatus(CacheStatus.HIT);
            sink.onToken("cached [S1]");
            sink.onComplete(new Completion(FinishReason.STOP, Usage.ZERO, "claude-haiku-5-5"));
        };

        RagService.Result result = service.answer(CALLER, question("q", RetrievalMode.HYBRID));

        assertThat(result.headers()).containsEntry(GatewayHeaders.PROVIDER, "cache")
                .containsEntry(GatewayHeaders.MODEL, "claude-haiku-5-5")
                .containsEntry(GatewayHeaders.CACHE, "HIT")
                .containsEntry(GatewayHeaders.ROUTE_RULE, "short");
        assertThat(result.body().usage().totalTokens()).isZero();
        assertThat(result.body().citations()).extracting(RagAnswer.Citation::source).containsExactly("S1");
    }

    @Test
    void noSourcesSkipsThePipeline() {
        retriever.returning(List.of());

        RagService.Result result = service.answer(CALLER, question("q", RetrievalMode.HYBRID));

        assertThat(contexts).as("the pipeline did not run").isEmpty();
        RagAnswer body = result.body();
        assertThat(body.noAnswer()).isTrue();
        assertThat(body.answer()).isEqualTo(RagAnswer.NO_ANSWER_TEXT);
        assertThat(body.citations()).isEmpty();
        assertThat(body.sources()).isEmpty();
        assertThat(body.model()).isNull();
        assertThat(body.usage().totalTokens()).isZero();
        assertThat(result.headers()).isEmpty();
        assertThat(queries(RagService.NO_SOURCES)).isEqualTo(1.0);
        assertThat(queries(RagService.ANSWERED)).isZero();
    }

    @Test
    void aDegradedRetrievalReportsTheModeThatRan() {
        retriever.returning(FakeRetriever.chunks(1)).degradingHybridToKeyword();

        RagService.Result result = service.answer(CALLER, question("q", RetrievalMode.HYBRID));

        assertThat(result.body().retrievalMode()).isEqualTo("keyword");
    }

    @Test
    void aPipelineErrorIsRethrownWithItsCodeAndHeaders() {
        retriever.returning(FakeRetriever.chunks(1));
        script = (ctx, sink) -> sink.onError(new RateLimitedException(3_000));

        assertThatThrownBy(() -> service.answer(CALLER, question("q", RetrievalMode.HYBRID)))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(e.responseHeaders()).containsKey(GatewayHeaders.RETRY_AFTER);
                });
        assertThat(queries(RagService.ERROR)).isEqualTo(1.0);
    }

    @Test
    void anUnexpectedPipelineErrorBecomesInternalError() {
        retriever.returning(FakeRetriever.chunks(1));
        script = (ctx, sink) -> sink.onError(new IllegalStateException("secret internals"));

        assertThatThrownBy(() -> service.answer(CALLER, question("q", RetrievalMode.HYBRID)))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(e.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void aRetrievalFailureIsCountedAndPropagated() {
        retriever.failingWith(new GatewayException(ErrorCode.ALL_PROVIDERS_FAILED, "embedder unavailable"));

        assertThatThrownBy(() -> service.answer(CALLER, question("q", RetrievalMode.VECTOR)))
                .isInstanceOf(GatewayException.class);
        assertThat(contexts).isEmpty();
        assertThat(queries(RagService.ERROR)).isEqualTo(1.0);
    }

    @Test
    void aDatabaseFailureInRetrievalBecomesAFixedInternalError() {
        retriever.failingWith(new org.springframework.dao.DataAccessResourceFailureException(
                "Connection to db-host:5432 refused: SELECT secret FROM chunks"));

        assertThatThrownBy(() -> service.answer(CALLER, question("q", RetrievalMode.HYBRID)))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(e.getMessage()).isEqualTo(RagService.RETRIEVAL_FAILED)
                            .doesNotContain("db-host").doesNotContain("SELECT");
                });
        assertThat(contexts).isEmpty();
        assertThat(queries(RagService.ERROR)).isEqualTo(1.0);
    }

    @Test
    void ragQueriesCarryOnlyTenantAndResult() {
        retriever.returning(FakeRetriever.chunks(1));
        service.answer(CALLER, question("q", RetrievalMode.HYBRID));

        registry.find(MetricNames.RAG_QUERIES).counters().forEach(c ->
                assertThat(c.getId().getTags()).extracting(t -> t.getKey()).containsExactlyInAnyOrder("tenant", "result"));
    }
}
