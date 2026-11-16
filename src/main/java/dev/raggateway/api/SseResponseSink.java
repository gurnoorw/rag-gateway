package dev.raggateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.api.JsonResponseSink.ServedBy;
import dev.raggateway.api.dto.ChatCompletionChunk;
import dev.raggateway.api.dto.ChatCompletionChunk.ChunkChoice;
import dev.raggateway.api.dto.ChatCompletionChunk.Delta;
import dev.raggateway.api.dto.UsageDto;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.Completion;
import dev.raggateway.core.RequestContext;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Client sink for {@code stream:true}, written straight to the servlet response on the request's own
 * virtual thread (DECISIONS.md D-03). Status and headers are committed lazily at the first byte (C15):
 *
 * <ul>
 *   <li>before the first byte, an error is a real HTTP status with a JSON {@link ErrorResponse};</li>
 *   <li>after it, an error is one {@code data: {"error":{...}}} event, then the stream is closed without
 *       {@code [DONE]};</li>
 *   <li>a normal end writes the final chunk with {@code finish_reason}, the usage chunk when the caller set
 *       {@code stream_options.include_usage}, then {@code data: [DONE]}, then closes the output stream.</li>
 * </ul>
 *
 * Every event is flushed at once (no buffering, AC-t01-5). A failed write means the client is gone: the sink
 * throws {@link ClientDisconnectedException} so the provider adapter cancels the upstream call (C23), and
 * ignores later events.
 */
public class SseResponseSink implements StreamSink {

    private static final Logger log = LoggerFactory.getLogger(SseResponseSink.class);
    private static final byte[] DONE = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);

    private final HttpServletResponse response;
    private final RequestContext ctx;
    private final ObjectMapper json;
    private final GatewayProperties properties;
    private final long createdEpochSeconds = System.currentTimeMillis() / 1000;
    private OutputStream out;
    private ServedBy served;
    private boolean roleSent;
    private boolean terminated;
    private boolean disconnected;

    public SseResponseSink(HttpServletResponse response, RequestContext ctx, ObjectMapper json,
                           GatewayProperties properties) {
        this.response = response;
        this.ctx = ctx;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public void onToken(String text) {
        if (disconnected) throw new ClientDisconnectedException(null);
        if (terminated) return;
        start();
        ctx.markFirstTokenSent(System.nanoTime());
        Delta delta = new Delta(roleSent ? null : "assistant", text);
        roleSent = true;
        writeEvent(chunk(List.of(new ChunkChoice(0, delta, null)), null));
        flush();
    }

    @Override
    public void onComplete(Completion completion) {
        if (terminated || disconnected) return;
        terminated = true;
        start();
        writeEvent(chunk(List.of(new ChunkChoice(0, new Delta(roleSent ? null : "assistant", null),
                completion.finishReason().wire())), null));
        if (ctx.request().includeUsage()) writeEvent(chunk(List.of(), UsageDto.of(completion.usage())));
        write(DONE);
        flush();
        close();
    }

    @Override
    public void onError(Throwable error) {
        if (terminated) return;
        terminated = true;
        if (disconnected) return;
        if (out == null) {
            JsonResponseSink.writeJsonError(response, ctx, json, error);
            return;
        }
        var body = ErrorResponse.of(JsonResponseSink.asGatewayException(error), ctx.requestId());
        try {
            out.write(event(JsonResponseSink.serialize(json, body)));
            out.flush();
            out.close();
        } catch (IOException e) {
            log.debug("client went away before the stream error event was written");
        }
    }

    /** True once a terminal event (complete or error) was handled. */
    public boolean terminated() {
        return terminated;
    }

    /** True once a write failed because the client went away. */
    public boolean disconnected() {
        return disconnected;
    }

    /** Commit status and headers; runs once, at the first byte. */
    private void start() {
        if (out != null) return;
        served = ServedBy.resolve(ctx, properties, null);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        JsonResponseSink.applyServedHeaders(response, ctx, served);
        try {
            out = response.getOutputStream();
        } catch (IOException e) {
            throw disconnect(e);
        }
    }

    private ChatCompletionChunk chunk(List<ChunkChoice> choices, UsageDto usage) {
        return new ChatCompletionChunk(JsonResponseSink.completionId(ctx), ChatCompletionChunk.OBJECT,
                createdEpochSeconds, served.model(), choices, usage);
    }

    private void writeEvent(Object payload) {
        write(event(JsonResponseSink.serialize(json, payload)));
    }

    private static byte[] event(byte[] jsonBytes) {
        byte[] bytes = new byte[jsonBytes.length + 8];
        System.arraycopy("data: ".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 6);
        System.arraycopy(jsonBytes, 0, bytes, 6, jsonBytes.length);
        bytes[bytes.length - 2] = '\n';
        bytes[bytes.length - 1] = '\n';
        return bytes;
    }

    private void write(byte[] bytes) {
        try {
            out.write(bytes);
        } catch (IOException e) {
            throw disconnect(e);
        }
    }

    private void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw disconnect(e);
        }
    }

    private void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw disconnect(e);
        }
    }

    private ClientDisconnectedException disconnect(IOException cause) {
        disconnected = true;
        return new ClientDisconnectedException(cause);
    }
}
