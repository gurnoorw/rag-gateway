package dev.raggateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.api.dto.ChatCompletionRequest;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.RequestContext;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.GatewayPipeline;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/chat/completions}, OpenAI-compatible. Runs on the request's own virtual thread and
 * writes the servlet response itself through a client sink (DECISIONS.md D-03, C15), so nothing is
 * committed before the pipeline produces its first byte or its error.
 *
 * <p>Order at the edge (C3): request id ({@link RequestIdFilter}) → API key
 * ({@link dev.raggateway.auth.ApiKeyAuthInterceptor}, 401) → body parsing and mapping (400) → pipeline.
 */
@RestController
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ChatRequestMapper mapper;
    private final GatewayPipeline pipeline;
    private final ObjectMapper json;
    private final GatewayProperties properties;

    /** The pipeline is resolved on first use (it is provided by the core configuration). */
    public ChatCompletionsController(ChatRequestMapper mapper, @Lazy GatewayPipeline pipeline, ObjectMapper json,
                                     GatewayProperties properties) {
        this.mapper = mapper;
        this.pipeline = pipeline;
        this.json = json;
        this.properties = properties;
    }

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(@RequestBody ChatCompletionRequest body,
                                ApiKeyPrincipal caller,
                                @RequestHeader(name = GatewayHeaders.CACHE_BYPASS, required = false) String cacheBypass,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        ChatRequest chatRequest = mapper.toChatRequest(body);
        var ctx = new RequestContext(RequestIdFilter.requestId(request), RequestIdFilter.startNanos(request),
                caller, chatRequest);
        ctx.setCacheBypassRequested(cacheBypass != null && "true".equalsIgnoreCase(cacheBypass.trim()));

        if (chatRequest.stream()) {
            var sink = new SseResponseSink(response, ctx, json, properties);
            pipeline.run(ctx, sink);
            if (!sink.terminated() && !sink.disconnected()) endWithoutTerminalEvent(sink::onError);
        } else {
            var sink = new JsonResponseSink(response, ctx, json, properties);
            pipeline.run(ctx, sink);
            if (!sink.terminated() && !sink.disconnected()) endWithoutTerminalEvent(sink::onError);
        }
    }

    /** Defensive: the pipeline contract guarantees a terminal event; if it is broken, the client still gets an answer. */
    private static void endWithoutTerminalEvent(java.util.function.Consumer<Throwable> onError) {
        log.error("pipeline returned without a terminal event");
        onError.accept(new GatewayException(ErrorCode.INTERNAL_ERROR, "internal error"));
    }
}
