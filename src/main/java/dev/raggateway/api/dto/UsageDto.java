package dev.raggateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.raggateway.core.Usage;

/** OpenAI {@code usage} object. */
public record UsageDto(@JsonProperty("prompt_tokens") int promptTokens,
                       @JsonProperty("completion_tokens") int completionTokens,
                       @JsonProperty("total_tokens") int totalTokens) {

    public static UsageDto of(Usage usage) {
        return new UsageDto(usage.inputTokens(), usage.outputTokens(), usage.total());
    }
}
