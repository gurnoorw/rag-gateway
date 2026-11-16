package dev.raggateway.api;

import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.ClientDisconnectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders failures that happen before the pipeline runs (auth, body parsing, mapping) and unexpected
 * exceptions as the OpenAI-shaped {@link ErrorResponse} JSON (DECISIONS.md C15). Failures inside the
 * pipeline never reach this class: the client sink renders them.
 *
 * <p>The body is always {@code application/json}, whatever the client's {@code Accept} header, so an SSE
 * client still gets a readable error. Messages never echo the request body or credentials.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> gatewayException(GatewayException e, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        e.responseHeaders().forEach(headers::set);
        return ResponseEntity.status(e.code().httpStatus()).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(e, RequestIdFilter.requestId(request)));
    }

    /** The client went away; there is nobody to answer. Taking the response marks the request as handled. */
    @ExceptionHandler(ClientDisconnectedException.class)
    public void clientDisconnected(ClientDisconnectedException e, HttpServletResponse response) {
        log.debug("client disconnected");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request, HttpServletResponse response) {
        log.error("unhandled exception at the HTTP edge", e);
        if (response.isCommitted()) return null;
        return gatewayException(new GatewayException(ErrorCode.INTERNAL_ERROR, "internal error"), request);
    }

    /** Spring MVC's own exceptions (unreadable body, unsupported media type, unknown path...): same body shape. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (request instanceof ServletWebRequest swr && swr.getResponse() != null && swr.getResponse().isCommitted()) {
            return null;
        }
        ErrorCode code = codeFor(statusCode);
        String message;
        if (ex instanceof HttpMessageNotReadableException) {
            message = "request body is missing, is not valid JSON, or has a field of the wrong type";
        } else if (body instanceof ProblemDetail pd && pd.getDetail() != null) {
            message = pd.getDetail();
        } else {
            message = code.code().replace('_', ' ');
        }
        String requestId = (String) request.getAttribute(RequestIdFilter.ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(statusCode).headers(responseHeaders)
                .body(new ErrorResponse(new ErrorResponse.Body(message, code.type(), code.code(), requestId)));
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }
}
