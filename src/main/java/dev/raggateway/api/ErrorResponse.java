package dev.raggateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.raggateway.error.GatewayException;

/**
 * Error body, OpenAI shape: {@code {"error":{"message","type","code","request_id"}}}. The same JSON is
 * the HTTP body before the first token and the {@code data:} of the SSE error event after it
 * (DECISIONS.md C15). OpenAI SDKs read {@code error.message}, {@code error.type} and {@code error.code}.
 */
public record ErrorResponse(Body error) {

    public record Body(String message, String type, String code,
                       @JsonProperty("request_id") String requestId) {
    }

    public static ErrorResponse of(GatewayException e, String requestId) {
        return new ErrorResponse(new Body(e.getMessage(), e.code().type(), e.code().code(), requestId));
    }
}
