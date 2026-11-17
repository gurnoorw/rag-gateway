package dev.raggateway.rag.query;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.api.dto.UsageDto;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Usage;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.GatewayPipeline;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievalResult;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Answers a RAG question (t10; LLD "Answering"; DECISIONS.md C16, C17, D-39, D-49).
 *
 * <ol>
 *   <li><b>Retrieve</b> with the caller key's tenant and ACL groups (C16, C17); nothing from the request body
 *       reaches those two fields.</li>
 *   <li><b>No chunks</b> → the fixed {@link RagAnswer#NO_ANSWER_TEXT} with {@code no_answer: true}. The pipeline
 *       does not run: no provider call, no request-log row, no tokens, no cost.</li>
 *   <li><b>Answer</b>: the {@link RequestContext} is created now, after retrieval, with the request's own start
 *       time, and the retrieval time is added as a wait ({@link RequestContext#addWaitNanos}), so the D-39 overhead
 *       histogram means the same as for chat (retrieval has its own timers). A non-streaming {@link ChatRequest}
 *       ({@code auto}, {@code temperature} 0, {@code max_tokens} from {@code gateway.rag.answer}) built by
 *       {@link RagPromptBuilder} goes through the same {@link GatewayPipeline} as chat with a
 *       {@link CollectingSink}: auth was done at the edge; cache, rate limit and budget, routing, fallback,
 *       validation, the request log and metrics apply without new code. {@code temperature} 0 makes it
 *       cache-eligible (D-40).</li>
 *   <li><b>Retrieval errors</b>: a {@link GatewayException} (vector mode with the embedder down: 503
 *       {@code service_unavailable}) passes through. A database that is unreachable, out of pool connections or
 *       timing out becomes 503 {@code service_unavailable}; any other {@link DataAccessException} (a bug) becomes
 *       500 {@code internal_error}. Both have fixed messages and no driver text (D-50).</li>
 *   <li><b>Errors</b> from the pipeline are rethrown as the same {@link GatewayException} (code, message, headers
 *       such as {@code Retry-After}), so the HTTP edge renders the status and body chat would have.</li>
 * </ol>
 *
 * <p>Counts one {@code rag.queries{tenant,result}} per query that reached retrieval: {@code answered},
 * {@code no_sources} or {@code error}. Logs one INFO line per query with the mode used, the result and the chunk ids
 * in the prompt (ids only, never text); the request id is on every line through the MDC.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    static final String ANSWERED = "answered";
    static final String NO_SOURCES = "no_sources";
    static final String ERROR = "error";

    /**
     * Message of the 500 for a database failure during retrieval. Fixed: the driver's message can hold SQL or
     * connection details, which stay out of the response and the log line (only exception class names are logged).
     */
    static final String RETRIEVAL_FAILED = "retrieval failed; try again";

    /** Message of the 503 when the database cannot serve retrieval right now (D-50). */
    static final String RETRIEVAL_UNAVAILABLE = "retrieval is temporarily unavailable; retry shortly";

    private final Retriever retriever;
    private final GatewayPipeline pipeline;
    private final RagProperties.Answer settings;
    private final MeterRegistry registry;
    private final LongSupplier nanoClock;

    @Autowired
    public RagService(Retriever retriever, GatewayPipeline pipeline, RagProperties properties,
                      MeterRegistry registry) {
        this(retriever, pipeline, properties.answer(), registry, System::nanoTime);
    }

    RagService(Retriever retriever, GatewayPipeline pipeline, RagProperties.Answer settings, MeterRegistry registry,
               LongSupplier nanoClock) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * A validated question.
     *
     * @param collection  collection id
     * @param question    1..{@code max-question-chars} characters
     * @param topK        1..20
     * @param mode        legs to run
     * @param requestId   the edge's request id ({@code X-Request-Id})
     * @param startNanos  {@link System#nanoTime()} when the edge received the request
     * @param cacheBypass the caller sent {@code X-Gateway-Cache-Bypass: true}
     */
    public record Question(String collection, String question, int topK, RetrievalMode mode, String requestId,
                           long startNanos, boolean cacheBypass) {
        public Question {
            Objects.requireNonNull(collection, "collection");
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    /**
     * @param body    the 200 body
     * @param headers gateway response headers ({@code X-Gateway-Provider}, {@code -Model}, {@code -Cache},
     *                {@code -Route-Rule}); empty for a no-answer response, where no model was involved
     */
    public record Result(RagAnswer body, Map<String, String> headers) {
        public Result {
            headers = Map.copyOf(headers);
        }
    }

    /**
     * @throws GatewayException the pipeline's error (same code and headers as chat), or a retrieval failure
     */
    public Result answer(ApiKeyPrincipal caller, Question q) {
        String tenant = caller.tenant().id();
        RetrievalQuery query = new RetrievalQuery(tenant, q.collection(), q.question(), caller.groups(), q.topK(),
                q.mode());
        long retrievalStart = nanoClock.getAsLong();
        RetrievalResult retrieved;
        try {
            retrieved = retriever.retrieve(query);
        } catch (DataAccessResourceFailureException | TransientDataAccessException | CannotCreateTransactionException e) {
            // Unreachable database, pool timeout, query timeout: transient and retryable (D-50).
            count(tenant, ERROR);
            log.warn("rag query: result={} mode={} stage=retrieval error={} cause={}", ERROR, q.mode(),
                    e.getClass().getSimpleName(), rootCauseName(e));
            throw new GatewayException(ErrorCode.SERVICE_UNAVAILABLE, RETRIEVAL_UNAVAILABLE);
        } catch (DataAccessException e) {
            // Anything else (bad SQL, a constraint) is our fault, not the caller's or the database's capacity.
            count(tenant, ERROR);
            log.warn("rag query: result={} mode={} stage=retrieval error={} cause={}", ERROR, q.mode(),
                    e.getClass().getSimpleName(), rootCauseName(e));
            throw new GatewayException(ErrorCode.INTERNAL_ERROR, RETRIEVAL_FAILED);
        } catch (RuntimeException e) {
            count(tenant, ERROR);
            log.info("rag query: result={} mode={} stage=retrieval error={}", ERROR, q.mode(), e.getClass().getSimpleName());
            throw e;
        }
        long retrievalNanos = nanoClock.getAsLong() - retrievalStart;
        List<RetrievedChunk> chunks = retrieved.chunks();
        String modeUsed = wire(retrieved.modeUsed());
        List<RagAnswer.Source> sources = RagAnswer.sources(chunks);
        List<String> chunkIds = chunks.stream().map(RetrievedChunk::chunkId).toList();

        if (chunks.isEmpty()) {
            count(tenant, NO_SOURCES);
            log.info("rag query: result={} mode={} chunks=[]", NO_SOURCES, modeUsed);
            return new Result(new RagAnswer(RagAnswer.NO_ANSWER_TEXT, List.of(), List.of(), true, modeUsed, null,
                    UsageDto.of(Usage.ZERO), q.requestId()), Map.of());
        }

        ChatRequest request = new ChatRequest(ChatRequest.AUTO, RagPromptBuilder.messages(chunks, q.question()),
                settings.maxTokens(), 0.0, null, List.of(), false, false, null);
        RequestContext ctx = new RequestContext(q.requestId(), q.startNanos(), caller, request);
        ctx.setCacheBypassRequested(q.cacheBypass());
        ctx.addWaitNanos(retrievalNanos);
        CollectingSink sink = new CollectingSink(ctx);
        pipeline.run(ctx, sink);

        Completion completion = sink.completion();
        if (completion == null) {
            GatewayException error = asGatewayException(sink.error());
            count(tenant, ERROR);
            log.info("rag query: result={} mode={} code={} chunks={}", ERROR, modeUsed, error.code().code(), chunkIds);
            throw error;
        }
        String answer = sink.text();
        List<RagAnswer.Citation> citations = RagAnswer.citations(answer, sources);
        Map<String, String> headers = servedHeaders(ctx);
        count(tenant, ANSWERED);
        log.info("rag query: result={} mode={} cache={} chunks={} cited={}", ANSWERED, modeUsed, ctx.cacheStatus(),
                chunkIds, citations.stream().map(RagAnswer.Citation::source).toList());
        return new Result(new RagAnswer(answer, citations, sources, false, modeUsed, headers.get(GatewayHeaders.MODEL),
                UsageDto.of(completion.usage()), q.requestId()), headers);
    }

    /**
     * The same "served by" rule as the chat endpoint (D-28): a cache hit is served by {@code cache} with the
     * resolved model; otherwise the attempt the terminal announced last (a fallback included).
     */
    private static Map<String, String> servedHeaders(RequestContext ctx) {
        String provider;
        String model;
        if (ctx.cacheStatus() == CacheStatus.HIT) {
            provider = MetricNames.PROVIDER_CACHE;
            model = ctx.request().model();
        } else if (ctx.currentProvider() != null) {
            provider = ctx.currentProvider();
            model = ctx.currentModel();
        } else {
            provider = "unknown";
            model = ctx.request().model();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(GatewayHeaders.PROVIDER, provider);
        headers.put(GatewayHeaders.MODEL, model);
        headers.put(GatewayHeaders.CACHE, ctx.cacheStatus().name());
        if (ctx.routeRule() != null) headers.put(GatewayHeaders.ROUTE_RULE, ctx.routeRule());
        return headers;
    }

    /** Unknown failures become 500 {@code internal_error} with a fixed message, as on the chat endpoint. */
    private static GatewayException asGatewayException(Throwable error) {
        if (error instanceof GatewayException ge) return ge;
        log.error("rag answer failed with an unexpected error", error);
        return new GatewayException(ErrorCode.INTERNAL_ERROR, "internal error");
    }

    private void count(String tenant, String result) {
        registry.counter(MetricNames.RAG_QUERIES, "tenant", tenant, "result", result).increment();
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName();
    }

    static String wire(RetrievalMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
