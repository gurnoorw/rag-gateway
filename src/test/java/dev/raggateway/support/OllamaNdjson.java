package dev.raggateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds Ollama {@code POST /api/chat} streaming bodies for WireMock (t03 round prep). Ollama is not reachable
 * in the build environment (D-21), so these shapes are taken from the official API docs, not from memory:
 * {@code docs/api.md} ("Generate a chat completion", streaming example and final object) and
 * {@code docs/api/errors.mdx} ("Errors that occur while streaming") in github.com/ollama/ollama at commit
 * db5266a7, read 2026-11-16.
 *
 * <ul>
 *   <li>Content type {@code application/x-ndjson}: one JSON object per line.</li>
 *   <li>Each piece: {@code {"model","created_at","message":{"role":"assistant","content":"..."},"done":false}}.</li>
 *   <li>Final object: {@code "done":true}, {@code "done_reason"} ({@code stop} or {@code length}), empty content,
 *       {@code prompt_eval_count} (input tokens) and {@code eval_count} (output tokens), durations in ns.</li>
 *   <li>An error before the stream starts is an HTTP status with {@code {"error":"..."}}; an error mid-stream is
 *       one more line {@code {"error":"..."}} with the status already 200.</li>
 * </ul>
 *
 * <pre>{@code
 * String body = OllamaNdjson.start("llama3.2:1b").text("Hel").text("lo").end("stop", 26, 2).body();
 * ollama.stubFor(post("/api/chat").willReturn(okForContentType(OllamaNdjson.CONTENT_TYPE, body)));
 * }</pre>
 */
public final class OllamaNdjson {

    public static final String CONTENT_TYPE = "application/x-ndjson";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATED_AT = "2026-11-16T10:00:00.000000Z";

    private final String model;
    private final List<String> lines = new ArrayList<>();

    private OllamaNdjson(String model) {
        this.model = model;
    }

    public static OllamaNdjson start(String model) {
        return new OllamaNdjson(model);
    }

    /** One streamed piece of assistant text. */
    public OllamaNdjson text(String content) {
        ObjectNode line = base(false);
        line.putObject("message").put("role", "assistant").put("content", content);
        lines.add(line.toString());
        return this;
    }

    /** The final object with the stop reason and token counts. */
    public OllamaNdjson end(String doneReason, int promptEvalCount, int evalCount) {
        ObjectNode line = base(true);
        line.putObject("message").put("role", "assistant").put("content", "");
        line.put("done_reason", doneReason)
                .put("total_duration", 4_883_583_458L)
                .put("load_duration", 1_334_875L)
                .put("prompt_eval_count", promptEvalCount)
                .put("prompt_eval_duration", 342_546_000L)
                .put("eval_count", evalCount)
                .put("eval_duration", 4_535_599_000L);
        lines.add(line.toString());
        return this;
    }

    /** A mid-stream error line, as Ollama sends after the status is already 200. */
    public OllamaNdjson error(String message) {
        lines.add(JSON.createObjectNode().put("error", message).toString());
        return this;
    }

    /** The NDJSON body: each object on its own line. */
    public String body() {
        return String.join("\n", lines) + "\n";
    }

    /** The JSON body of an error returned with an HTTP error status, before any stream. */
    public static String errorBody(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    /** A complete reply: pieces, then {@code done_reason: stop} with the given counts. */
    public static String reply(String model, int promptEvalCount, int evalCount, String... pieces) {
        OllamaNdjson b = start(model);
        for (String p : pieces) b.text(p);
        return b.end("stop", promptEvalCount, evalCount).body();
    }

    private ObjectNode base(boolean done) {
        return JSON.createObjectNode().put("model", model).put("created_at", CREATED_AT).put("done", done);
    }
}
