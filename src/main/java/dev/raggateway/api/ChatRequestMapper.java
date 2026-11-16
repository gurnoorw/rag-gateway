package dev.raggateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.raggateway.api.dto.ChatCompletionRequest;
import dev.raggateway.api.dto.ChatCompletionRequest.ChatMessage;
import dev.raggateway.api.dto.ChatCompletionRequest.ResponseFormat;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.Role;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps the OpenAI request body to the provider-neutral {@link ChatRequest}, applying the supported
 * surface of DECISIONS.md D-16. Everything outside that surface fails with 400 {@code invalid_request}
 * instead of being dropped silently; unknown fields are ignored. Error messages never echo message
 * content.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code model} non-blank; {@code messages} non-empty with at least one user or assistant message;</li>
 *   <li>roles {@code system}, {@code user}, {@code assistant}; content must be a JSON string;</li>
 *   <li>all system messages are joined (blank line between them) into one leading system message, because
 *       providers take one system prompt;</li>
 *   <li>{@code max_completion_tokens} wins over {@code max_tokens}; absent both, {@code gateway.default-max-tokens};
 *       must be &gt; 0 (the upper cap is checked by {@code RequestValidationFilter});</li>
 *   <li>{@code temperature} in [0, 2], {@code top_p} in [0, 1], as OpenAI accepts;</li>
 *   <li>{@code stop}: string or list of strings;</li>
 *   <li>rejected: {@code n} other than 1, {@code tools}/{@code functions}, {@code response_format}
 *       {@code json_object} or any type other than {@code text}/{@code json_schema}.</li>
 * </ul>
 */
@Component
public class ChatRequestMapper {

    private final GatewayProperties properties;

    public ChatRequestMapper(GatewayProperties properties) {
        this.properties = properties;
    }

    public ChatRequest toChatRequest(ChatCompletionRequest body) {
        if (body == null) throw invalid("request body is required");
        if (body.model() == null || body.model().isBlank()) throw invalid("'model' is required");
        if (body.n() != null && body.n() != 1) throw invalid("only n = 1 is supported");
        if (present(body.tools())) throw invalid("'tools' is not supported");
        if (present(body.functions())) throw invalid("'functions' is not supported");

        List<Message> messages = mapMessages(body.messages());
        int maxTokens = maxTokens(body);
        if (body.temperature() != null && (body.temperature() < 0 || body.temperature() > 2)) {
            throw invalid("'temperature' must be between 0 and 2");
        }
        if (body.topP() != null && (body.topP() < 0 || body.topP() > 1)) {
            throw invalid("'top_p' must be between 0 and 1");
        }
        boolean stream = Boolean.TRUE.equals(body.stream());
        boolean includeUsage = body.streamOptions() != null && Boolean.TRUE.equals(body.streamOptions().includeUsage());

        return new ChatRequest(body.model(), messages, maxTokens, body.temperature(), body.topP(),
                stop(body.stop()), stream, includeUsage, jsonSchema(body.responseFormat()));
    }

    private static List<Message> mapMessages(List<ChatMessage> wire) {
        if (wire == null || wire.isEmpty()) throw invalid("'messages' must be a non-empty array");
        List<String> systemParts = new ArrayList<>();
        List<Message> conversation = new ArrayList<>();
        for (int i = 0; i < wire.size(); i++) {
            ChatMessage m = wire.get(i);
            if (m == null) throw invalid("messages[" + i + "] must be an object");
            Role role = role(m.role(), i);
            JsonNode content = m.content();
            if (content == null || !content.isTextual()) {
                throw invalid("messages[" + i + "].content must be a string; images, parts and tool calls are not supported");
            }
            if (role == Role.SYSTEM) systemParts.add(content.textValue());
            else conversation.add(new Message(role, content.textValue()));
        }
        if (conversation.isEmpty()) throw invalid("'messages' must contain at least one user or assistant message");
        List<Message> messages = new ArrayList<>(conversation.size() + 1);
        if (!systemParts.isEmpty()) messages.add(new Message(Role.SYSTEM, String.join("\n\n", systemParts)));
        messages.addAll(conversation);
        return messages;
    }

    private static Role role(String wire, int index) {
        if (wire == null) throw invalid("messages[" + index + "].role is required");
        return switch (wire) {
            case "system" -> Role.SYSTEM;
            case "user" -> Role.USER;
            case "assistant" -> Role.ASSISTANT;
            default -> throw invalid("messages[" + index + "].role must be system, user or assistant");
        };
    }

    private int maxTokens(ChatCompletionRequest body) {
        Integer requested = body.maxCompletionTokens() != null ? body.maxCompletionTokens() : body.maxTokens();
        if (requested == null) return properties.defaultMaxTokens();
        if (requested <= 0) throw invalid("'max_tokens' must be greater than 0");
        return requested;
    }

    private static List<String> stop(JsonNode stop) {
        if (stop == null || stop.isNull()) return List.of();
        if (stop.isTextual()) return List.of(stop.textValue());
        if (stop.isArray()) {
            List<String> values = new ArrayList<>(stop.size());
            for (JsonNode s : stop) {
                if (!s.isTextual()) throw invalid("'stop' must be a string or an array of strings");
                values.add(s.textValue());
            }
            return values;
        }
        throw invalid("'stop' must be a string or an array of strings");
    }

    private static String jsonSchema(ResponseFormat format) {
        if (format == null || format.type() == null || "text".equals(format.type())) return null;
        if ("json_schema".equals(format.type())) {
            if (format.jsonSchema() == null || format.jsonSchema().schema() == null
                    || !format.jsonSchema().schema().isObject()) {
                throw invalid("'response_format.json_schema.schema' must be a JSON object");
            }
            return format.jsonSchema().schema().toString();
        }
        if ("json_object".equals(format.type())) {
            throw invalid("response_format 'json_object' is not supported; use 'json_schema'");
        }
        throw invalid("response_format type must be 'text' or 'json_schema'");
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(ErrorCode.INVALID_REQUEST, message);
    }
}
