package dev.raggateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.api.dto.ChatCompletionResponse;
import dev.raggateway.api.dto.UsageDto;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.Completion;
import dev.raggateway.core.RequestContext;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client sink for {@code stream:false}: buffers the text and writes one {@code chat.completion} JSON
 * body with {@code Content-Length} on completion, or an error status with a JSON {@link ErrorResponse}
 * (DECISIONS.md C15). Nothing is written to the client before the terminal event, so every error is a
 * real HTTP status.
 *
 * <p>Also holds the helpers both client sinks share: the "served by" headers and the JSON error body.
 */
public class JsonResponseSink implements StreamSink {

    private static final Logger log = LoggerFactory.getLogger(JsonResponseSink.class);

    private final HttpServletResponse response;
    private final RequestContext ctx;
    private final ObjectMapper json;
    private final GatewayProperties properties;
    private final long createdEpochSeconds = System.currentTimeMillis() / 1000;
    private final StringBuilder text = new StringBuilder();
    private boolean terminated;
    private boolean disconnected;

    public JsonResponseSink(HttpServletResponse response, RequestContext ctx, ObjectMapper json,
                            GatewayProperties properties) {
        this.response = response;
        this.ctx = ctx;
        this.json = json;
        this.properties = properties;
    }

    /**
     * Buffers the token. The first token marks {@code firstTokenSent}: once text has entered this sink,
     * a retry would duplicate it in the buffered answer, so the same "no retry after the first token"
     * rule applies as for a stream.
     */
    @Override
    public void onToken(String token) {
        if (terminated) return;
        ctx.markFirstTokenSent(System.nanoTime());
        text.append(token);
    }

    @Override
    public void onComplete(Completion completion) {
        if (terminated) return;
        terminated = true;
        ServedBy served = ServedBy.resolve(ctx, properties, completion.model());
        var body = new ChatCompletionResponse(completionId(ctx), ChatCompletionResponse.OBJECT, createdEpochSeconds,
                served.model(),
                List.of(new ChatCompletionResponse.Choice(0,
                        new ChatCompletionResponse.AssistantMessage("assistant", text.toString()),
                        completion.finishReason().wire())),
                UsageDto.of(completion.usage()));
        byte[] bytes = serialize(json, body);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(bytes.length);
        applyServedHeaders(response, ctx, served);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            disconnected = true;
            throw new ClientDisconnectedException(e);
        }
    }

    @Override
    public void onError(Throwable error) {
        if (terminated) return;
        terminated = true;
        if (disconnected) return;
        writeJsonError(response, ctx, json, error);
    }

    /** True once a terminal event (complete or error) was handled. */
    public boolean terminated() {
        return terminated;
    }

    // ---- helpers shared with SseResponseSink ----

    /** OpenAI response id: {@code chatcmpl-} + request id (D-16). */
    static String completionId(RequestContext ctx) {
        return "chatcmpl-" + ctx.requestId();
    }

    /** Unknown failures become 500 {@code internal_error} with a fixed message: internals never reach the client. */
    static GatewayException asGatewayException(Throwable error) {
        if (error instanceof GatewayException ge) return ge;
        log.error("request failed with an unexpected error", error);
        return new GatewayException(ErrorCode.INTERNAL_ERROR, "internal error");
    }

    /** Error before anything was written: status, extra headers (e.g. {@code Retry-After}), JSON body. */
    static void writeJsonError(HttpServletResponse response, RequestContext ctx, ObjectMapper json, Throwable error) {
        GatewayException ge = asGatewayException(error);
        byte[] bytes = serialize(json, ErrorResponse.of(ge, ctx.requestId()));
        response.setStatus(ge.code().httpStatus());
        ge.responseHeaders().forEach(response::setHeader);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            log.debug("client went away before the error response was written");
        }
    }

    static void applyServedHeaders(HttpServletResponse response, RequestContext ctx, ServedBy served) {
        response.setHeader(GatewayHeaders.PROVIDER, served.provider());
        response.setHeader(GatewayHeaders.MODEL, served.model());
        response.setHeader(GatewayHeaders.CACHE, ctx.cacheStatus().name());
        if (ctx.routeRule() != null) response.setHeader(GatewayHeaders.ROUTE_RULE, ctx.routeRule());
    }

    static byte[] serialize(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    /**
     * Provider and model reported to the client ({@code X-Gateway-Provider}/{@code X-Gateway-Model} and the
     * body's {@code model}).
     *
     * <p>Resolution: a cache hit is served by {@code cache} with the request's model. Otherwise a
     * successful serving attempt already recorded in the context wins (for example when output was
     * validated before being replayed, t07). Otherwise, while the serving attempt is still running, the
     * model is the one reported in the completion if known, else the resolved request model, and the
     * provider is that model's provider in the catalog.
     */
    record ServedBy(String provider, String model) {

        static ServedBy resolve(RequestContext ctx, GatewayProperties properties, String completionModel) {
            if (ctx.cacheStatus() == CacheStatus.HIT) return new ServedBy("cache", ctx.request().model());
            Optional<Attempt> succeeded = ctx.servingAttempt().filter(a -> a.outcome() == AttemptOutcome.SUCCEEDED);
            if (succeeded.isPresent()) return new ServedBy(succeeded.get().provider(), succeeded.get().model());
            String model = completionModel != null ? publicName(properties, completionModel) : ctx.request().model();
            String provider = providerOf(properties, model)
                    .or(() -> providerOf(properties, ctx.request().model()))
                    .orElse("unknown");
            return new ServedBy(provider, model);
        }

        private static Optional<String> providerOf(GatewayProperties properties, String model) {
            return Optional.ofNullable(properties.models().get(model)).map(GatewayProperties.ModelProperties::provider);
        }

        /** Catalog name for an upstream model id; the id itself when the catalog does not list it. */
        private static String publicName(GatewayProperties properties, String upstreamOrPublic) {
            if (properties.models().containsKey(upstreamOrPublic)) return upstreamOrPublic;
            return properties.models().entrySet().stream()
                    .filter(e -> upstreamOrPublic.equals(e.getValue().upstreamId()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(upstreamOrPublic);
        }
    }
}
