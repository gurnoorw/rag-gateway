package dev.raggateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Body of {@code POST /v1/chat/completions}, OpenAI wire format. Jackson checks only syntax and field
 * types; the rules of DECISIONS.md D-16 are applied by {@link dev.raggateway.api.ChatRequestMapper}.
 * Fields that accept several JSON shapes ({@code content}, {@code stop}) or that are only checked for
 * presence ({@code tools}, {@code functions}) are kept as {@link JsonNode}. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        JsonNode stop,
        Boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions,
        @JsonProperty("response_format") ResponseFormat responseFormat,
        Integer n,
        JsonNode tools,
        JsonNode functions) {

    /** One message; {@code content} must be a JSON string (D-16), anything else is rejected by the mapper. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(String role, JsonNode content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
    }

    /** {@code {"type":"text"}} or {@code {"type":"json_schema","json_schema":{"name":..,"schema":{..}}}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseFormat(String type, @JsonProperty("json_schema") JsonSchemaSpec jsonSchema) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonSchemaSpec(String name, JsonNode schema, Boolean strict) {
    }
}
