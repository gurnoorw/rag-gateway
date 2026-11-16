package dev.raggateway.auth;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * API-key authentication for every {@code /v1/**} endpoint (DECISIONS.md D-14, D-22). Runs before the
 * request body is read, so a missing or unknown key is 401 {@code unauthorized} even when the body is
 * malformed, and nothing reaches the pipeline. The authenticated caller is stored as a request attribute
 * and injected into handlers by {@link CallerArgumentResolver}.
 *
 * <p>The presented key is never logged, stored or put in an error message (AC-t01-8); only the key id
 * of the resolved principal may be logged.
 */
@Component
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    static final String CALLER_ATTRIBUTE = ApiKeyAuthInterceptor.class.getName() + ".caller";
    private static final String BEARER = "Bearer ";

    private final ApiKeyAuthenticator authenticator;

    /** The authenticator is resolved on first use (it is provided by the key store configuration). */
    public ApiKeyAuthInterceptor(@Lazy ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (key == null) {
            throw new GatewayException(ErrorCode.UNAUTHORIZED,
                    "missing API key; send it as 'Authorization: Bearer <key>'");
        }
        Optional<ApiKeyPrincipal> caller = authenticator.authenticate(key);
        if (caller.isEmpty()) throw new GatewayException(ErrorCode.UNAUTHORIZED, "invalid API key");
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        return true;
    }

    /** The token of a {@code Bearer} header (scheme case-insensitive), or null when absent or blank. */
    static String bearerToken(String header) {
        if (header == null || header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
