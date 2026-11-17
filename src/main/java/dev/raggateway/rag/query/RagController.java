package dev.raggateway.rag.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.api.RequestIdFilter;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.ingest.DocumentUpsert;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * {@code POST /v1/rag/query} (t10, DECISIONS.md D-49).
 *
 * <p>Request: {@code {"collection", "question", "top_k"?, "mode"?}}. Unknown fields are ignored; in particular a
 * {@code tenant} or {@code groups} field in the body has no effect: both come from the API key (C16, C17).
 * <ul>
 *   <li>{@code collection}: required, the document API's id pattern;</li>
 *   <li>{@code question}: required string, not blank, at most {@code gateway.rag.answer.max-question-chars}
 *       characters (Unicode code points; default 2,000);</li>
 *   <li>{@code top_k}: integer 1–20, default {@code gateway.rag.answer.default-top-k} (5);</li>
 *   <li>{@code mode}: {@code hybrid} (default), {@code vector} or {@code keyword}.</li>
 * </ul>
 * A bad value is 400 {@code invalid_request} with a message naming the field (never echoing the value), before
 * retrieval runs. Fields are read as JSON nodes so a wrong type (for example {@code "top_k": "five"}) also names
 * its field, instead of Jackson's generic "unreadable body".
 *
 * <p>Order at the edge is chat's: request id → API key (401) → body (400) → {@link RagService}. Pipeline errors keep
 * chat's status, headers and body because {@link RagService} rethrows the pipeline's {@link GatewayException} and
 * {@link dev.raggateway.api.ApiExceptionHandler} renders it exactly as the chat sink would.
 */
@RestController
public class RagController {

    static final String PATH = "/v1/rag/query";

    private final RagService service;
    private final RagProperties.Answer settings;

    public RagController(RagService service, RagProperties properties) {
        this.service = Objects.requireNonNull(service, "service");
        this.settings = properties.answer();
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RagAnswer> query(@RequestBody(required = false) JsonNode body, ApiKeyPrincipal caller,
                                           @RequestHeader(name = GatewayHeaders.CACHE_BYPASS, required = false)
                                           String cacheBypass,
                                           HttpServletRequest request) {
        if (body == null || !body.isObject()) throw invalid("request body must be a JSON object");
        var question = new RagService.Question(
                collection(body.get("collection")),
                question(body.get("question")),
                topK(body.get("top_k")),
                mode(body.get("mode")),
                RequestIdFilter.requestId(request),
                RequestIdFilter.startNanos(request),
                cacheBypass != null && "true".equalsIgnoreCase(cacheBypass.trim()));
        RagService.Result result = service.answer(caller, question);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        result.headers().forEach(response::header);
        return response.body(result.body());
    }

    private static String collection(JsonNode node) {
        if (absent(node)) throw invalid("'collection' is required");
        if (!node.isTextual() || !DocumentUpsert.ID.matcher(node.textValue()).matches()) {
            throw invalid("'collection' must be a string matching " + DocumentUpsert.ID.pattern());
        }
        return node.textValue();
    }

    private String question(JsonNode node) {
        if (absent(node)) throw invalid("'question' is required");
        if (!node.isTextual()) throw invalid("'question' must be a string");
        String q = node.textValue();
        if (q.isBlank()) throw invalid("'question' must not be blank");
        if (q.codePointCount(0, q.length()) > settings.maxQuestionChars()) {
            throw invalid("'question' must be at most " + settings.maxQuestionChars() + " characters");
        }
        return q;
    }

    private int topK(JsonNode node) {
        if (absent(node)) return settings.defaultTopK();
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > 20) {
            throw invalid("'top_k' must be an integer from 1 to 20");
        }
        return node.intValue();
    }

    private static RetrievalMode mode(JsonNode node) {
        if (absent(node)) return RetrievalMode.HYBRID;
        if (node.isTextual()) {
            switch (node.textValue()) {
                case "hybrid" -> { return RetrievalMode.HYBRID; }
                case "vector" -> { return RetrievalMode.VECTOR; }
                case "keyword" -> { return RetrievalMode.KEYWORD; }
                default -> { }
            }
        }
        throw invalid("'mode' must be one of hybrid, vector, keyword");
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(ErrorCode.INVALID_REQUEST, message);
    }
}
