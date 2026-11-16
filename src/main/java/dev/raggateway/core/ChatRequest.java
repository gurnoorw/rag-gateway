package dev.raggateway.core;

import java.util.List;
import java.util.Objects;

/**
 * The gateway's provider-neutral chat request. Built by the HTTP edge from the OpenAI wire format
 * after syntactic validation; every later step only reads it. Routing (t06) replaces the model with
 * {@link #withModel(String)}; nothing else changes a request after the edge.
 *
 * <p>Differs from the LLD record (DECISIONS.md D-14): {@code temperature} is nullable (absent means
 * provider default, which is never cached), and {@code topP}, {@code stop} and {@code includeUsage}
 * are added because they change the output or the wire format.
 *
 * @param model        public model name from the catalog, or {@link #AUTO} before routing
 * @param messages     at least one message, immutable copy
 * @param maxTokens    output cap, already defaulted by the edge; must be &gt; 0
 * @param temperature  sampling temperature or {@code null}; used for cache eligibility, forwarded
 *                     to providers that accept it (not to Claude, D-05)
 * @param topP         nucleus sampling or {@code null}; same forwarding rule as temperature
 * @param stop         stop sequences, possibly empty
 * @param stream       caller wants SSE ({@code stream:true}); transport only, not part of the cache key
 * @param includeUsage caller set {@code stream_options.include_usage}; transport only
 * @param jsonSchema   JSON text of the schema from {@code response_format.json_schema.schema}, or
 *                     {@code null} when no structured output was requested (t07)
 */
public record ChatRequest(String model, List<Message> messages, int maxTokens, Double temperature,
                          Double topP, List<String> stop, boolean stream, boolean includeUsage,
                          String jsonSchema) {

    /** Model name meaning "let the router choose" (t06). */
    public static final String AUTO = "auto";

    public ChatRequest {
        Objects.requireNonNull(model, "model");
        messages = List.copyOf(messages);
        stop = stop == null ? List.of() : List.copyOf(stop);
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
    }

    /** Same request with another model; used by routing and by the fallback chain. */
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, maxTokens, temperature, topP, stop, stream,
                includeUsage, jsonSchema);
    }

    /** True when the caller asked for JSON-schema structured output. */
    public boolean hasSchema() {
        return jsonSchema != null;
    }
}
