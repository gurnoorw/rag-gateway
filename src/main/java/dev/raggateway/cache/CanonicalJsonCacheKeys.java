package dev.raggateway.cache;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@link CacheKeyStrategy} by canonical JSON and SHA-256 (t05, D-40, LLD "Response cache").
 *
 * <p>The canonical form is written field by field in a fixed order by this class, not by reflecting over
 * {@link ChatRequest}: a field added to the request later does not silently enter (or leave) the key; it has
 * to be added here, with the prefix version bumped. Shape:
 * <pre>
 * {"tenant":..,"model":..,"messages":[{"role":"user","content":..},..],"temperature":..,"top_p":..,
 *  "max_tokens":..,"stop":[..],"schema":{..keys sorted..}}
 * </pre>
 * Normalisation, so equal requests give equal keys (AC-t05-11):
 * <ul>
 *   <li>the schema is parsed and re-written with object keys sorted (recursively) and no whitespace; array
 *       order is kept (it is significant in JSON);</li>
 *   <li>numbers (temperature, top_p, numbers inside the schema) are written as the shortest decimal with
 *       trailing zeros stripped, so {@code 0}, {@code 0.0} and {@code -0.0} are one value, as are {@code 1}
 *       and {@code 1.0} in a schema;</li>
 *   <li>absent temperature / top_p / schema are written as {@code null}, so "absent" never equals a value.</li>
 * </ul>
 * Not normalised, on purpose: message text (whitespace in a prompt can change the answer) and the order of
 * stop sequences. Either costs a miss, never a wrong hit. Strings are hashed as JSON-escaped values, so no
 * delimiter collision is possible (the reason the LLD hashes JSON instead of joining strings).
 *
 * <p>A schema that does not parse (cannot happen after request validation, which compiles it) is hashed as
 * its raw text under a different field name, so it still produces a stable key and can never collide with a
 * parsed schema.
 *
 * <p>Thread-safe: no mutable state; a {@link MessageDigest} is created per call (cheap; it is not thread-safe).
 */
@Component
public final class CanonicalJsonCacheKeys implements CacheKeyStrategy {

    /** Key prefix; bump the version when the canonical form changes, so old entries are never misread. */
    public static final String PREFIX = "cache:v1:";

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper SCHEMA_READER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public String keyFor(String tenantId, ChatRequest request) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(request, "request");
        return PREFIX + HexFormat.of().formatHex(sha256(canonicalJson(tenantId, request)));
    }

    /** The bytes that are hashed; package-private so tests can show what is (and is not) in the key. */
    static byte[] canonicalJson(String tenantId, ChatRequest r) {
        var out = new ByteArrayOutputStream(256);
        try (JsonGenerator g = FACTORY.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("tenant", tenantId);
            g.writeStringField("model", r.model());
            g.writeArrayFieldStart("messages");
            for (Message m : r.messages()) {
                g.writeStartObject();
                g.writeStringField("role", m.role().name().toLowerCase(Locale.ROOT));
                g.writeStringField("content", m.content());
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeFieldName("temperature");
            writeDecimal(g, r.temperature());
            g.writeFieldName("top_p");
            writeDecimal(g, r.topP());
            g.writeNumberField("max_tokens", r.maxTokens());
            g.writeArrayFieldStart("stop");
            for (String s : r.stop()) g.writeString(s);
            g.writeEndArray();
            writeSchema(g, r.jsonSchema());
            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory stream: does not happen
        }
        return out.toByteArray();
    }

    private static void writeSchema(JsonGenerator g, String schema) throws IOException {
        if (schema == null) {
            g.writeNullField("schema");
            return;
        }
        JsonNode parsed;
        try {
            parsed = SCHEMA_READER.readTree(schema);
        } catch (JsonProcessingException e) {
            parsed = null;
        }
        if (parsed == null || parsed.isMissingNode()) {
            g.writeStringField("schema_raw", schema);
            return;
        }
        g.writeFieldName("schema");
        writeCanonical(g, parsed);
    }

    /** Object keys sorted, arrays in order, numbers normalised, no whitespace. */
    private static void writeCanonical(JsonGenerator g, JsonNode node) throws IOException {
        switch (node.getNodeType()) {
            case OBJECT -> {
                List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(node.properties());
                fields.sort(Map.Entry.comparingByKey());
                g.writeStartObject();
                for (var f : fields) {
                    g.writeFieldName(f.getKey());
                    writeCanonical(g, f.getValue());
                }
                g.writeEndObject();
            }
            case ARRAY -> {
                g.writeStartArray();
                for (JsonNode element : node) writeCanonical(g, element);
                g.writeEndArray();
            }
            case NUMBER -> g.writeNumber(normalise(node.decimalValue()));
            case STRING -> g.writeString(node.textValue());
            case BOOLEAN -> g.writeBoolean(node.booleanValue());
            case NULL -> g.writeNull();
            default -> g.writeString(node.asText()); // binary/POJO/missing: not produced by readTree
        }
    }

    private static void writeDecimal(JsonGenerator g, Double value) throws IOException {
        if (value == null) {
            g.writeNull();
        } else if (value.isNaN() || value.isInfinite()) {
            g.writeString(value.toString()); // not valid JSON input; kept distinct and stable anyway
        } else {
            g.writeNumber(normalise(BigDecimal.valueOf(value)));
        }
    }

    private static String normalise(BigDecimal d) {
        return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
