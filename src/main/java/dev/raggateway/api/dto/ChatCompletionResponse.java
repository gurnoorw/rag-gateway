package dev.raggateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** {@code stream:false} response, OpenAI {@code chat.completion} object (D-16). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(String id, String object, long created, String model,
                                     List<Choice> choices, UsageDto usage) {

    public static final String OBJECT = "chat.completion";

    public record Choice(int index, AssistantMessage message,
                         @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("finish_reason") String finishReason) {
    }

    public record AssistantMessage(String role, String content) {
    }
}
