package dev.raggateway.error;

import java.util.Map;
import java.util.Objects;

/**
 * A failure the client sees, with its code. Unchecked (the LLD declares it on signatures; it stays
 * declared there for documentation), so it can be thrown from lambdas and sink callbacks (D-14).
 * The message is shown to the client: never put secrets, prompt text or stack traces in it.
 */
public class GatewayException extends RuntimeException {

    private final ErrorCode code;

    public GatewayException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public GatewayException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }

    /** Extra response headers, e.g. {@code Retry-After}. Empty by default. */
    public Map<String, String> responseHeaders() {
        return Map.of();
    }
}
