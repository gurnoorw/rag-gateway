package dev.raggateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One SSE event of a {@code stream:true} response, OpenAI {@code chat.completion.chunk} object.
 * Content chunks carry a delta; the final chunk carries an empty delta and the finish reason; the
 * optional usage chunk ({@code stream_options.include_usage}) has empty {@code choices} and a {@code usage}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(String id, String object, long created, String model,
                                  List<ChunkChoice> choices, UsageDto usage) {

    public static final String OBJECT = "chat.completion.chunk";

    /** {@code finish_reason} is always written, as {@code null} on content chunks, as OpenAI does. */
    public record ChunkChoice(int index, Delta delta,
                              @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("finish_reason") String finishReason) {
    }

    /** Role is sent on the first content chunk only; the final chunk has an empty delta. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {
    }
}
