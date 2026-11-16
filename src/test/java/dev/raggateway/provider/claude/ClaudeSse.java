package dev.raggateway.provider.claude;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds Messages-API SSE bodies for WireMock stubs (D-04 consequence): {@code message_start},
 * content blocks with deltas, {@code message_delta} with the stop reason and cumulative output tokens,
 * {@code message_stop}. Shapes follow the Claude streaming reference; ids are fake.
 */
public final class ClaudeSse {

    private final List<String> events = new ArrayList<>();
    private int blockIndex = -1;

    public static ClaudeSse start(String model, int inputTokens) {
        var sse = new ClaudeSse();
        sse.event("message_start", """
                {"type":"message_start","message":{"id":"msg_test_01","type":"message","role":"assistant",\
                "model":"%s","content":[],"stop_reason":null,"stop_sequence":null,\
                "usage":{"input_tokens":%d,"output_tokens":1}}}""".formatted(model, inputTokens));
        return sse;
    }

    public ClaudeSse ping() {
        return event("ping", "{\"type\":\"ping\"}");
    }

    /** A thinking block with empty thinking text (display "omitted", the default) and a signature. */
    public ClaudeSse thinkingBlock() {
        blockIndex++;
        event("content_block_start", """
                {"type":"content_block_start","index":%d,"content_block":{"type":"thinking","thinking":"","signature":""}}"""
                .formatted(blockIndex));
        event("content_block_delta", """
                {"type":"content_block_delta","index":%d,"delta":{"type":"thinking_delta","thinking":""}}"""
                .formatted(blockIndex));
        event("content_block_delta", """
                {"type":"content_block_delta","index":%d,"delta":{"type":"signature_delta","signature":"c2lnbmF0dXJl"}}"""
                .formatted(blockIndex));
        return event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":%d}".formatted(blockIndex));
    }

    public ClaudeSse textBlockStart() {
        blockIndex++;
        return event("content_block_start", """
                {"type":"content_block_start","index":%d,"content_block":{"type":"text","text":""}}"""
                .formatted(blockIndex));
    }

    public ClaudeSse text(String text) {
        return event("content_block_delta", """
                {"type":"content_block_delta","index":%d,"delta":{"type":"text_delta","text":"%s"}}"""
                .formatted(blockIndex, text.replace("\\", "\\\\").replace("\"", "\\\"")));
    }

    public ClaudeSse blockStop() {
        return event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":%d}".formatted(blockIndex));
    }

    public ClaudeSse end(String stopReason, int outputTokens) {
        event("message_delta", """
                {"type":"message_delta","delta":{"stop_reason":"%s","stop_sequence":null},"usage":{"output_tokens":%d}}"""
                .formatted(stopReason, outputTokens));
        return event("message_stop", "{\"type\":\"message_stop\"}");
    }

    public ClaudeSse error(String type, String message) {
        return event("error", """
                {"type":"error","error":{"type":"%s","message":"%s"}}""".formatted(type, message));
    }

    public ClaudeSse event(String name, String json) {
        events.add("event: " + name + "\ndata: " + json + "\n\n");
        return this;
    }

    /** Events as separate SSE frames (for servers that send them one by one). */
    public List<String> frames() {
        return List.copyOf(events);
    }

    public String body() {
        return String.join("", events);
    }

    /** A complete reply: message_start, one text block with the pieces, end_turn. */
    public static String reply(String model, int in, int out, String... pieces) {
        ClaudeSse sse = start(model, in).textBlockStart();
        for (String p : pieces) sse.text(p);
        return sse.blockStop().end("end_turn", out).body();
    }
}
