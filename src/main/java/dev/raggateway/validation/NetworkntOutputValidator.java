package dev.raggateway.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.InputStreamSource;
import com.networknt.schema.resource.SchemaLoader;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@link OutputValidator} on networknt json-schema-validator 1.5.9 (DECISIONS.md D-10): draft 2020-12 unless the
 * schema names another draft in {@code $schema}.
 *
 * <p><b>Schema check</b> ({@link #checkSchema}, AC-t07-6). Compiling alone is not enough: networknt compiles
 * {@code {"type": 5}} without complaint and then rejects every output, which would turn a caller's mistake into two
 * paid provider calls and a 502. So the schema is first validated against its draft's meta-schema, then compiled
 * with every validator initialised (bad regex, unresolvable {@code $ref}).
 *
 * <p><b>No remote loading.</b> The schema comes from the caller, so a {@code $ref} or {@code $schema} pointing at a
 * URL would make the gateway fetch it (server-side request forgery, and a slow host would stall the request). The
 * factory's first loader refuses every IRI except the draft meta-schemas bundled in the validator's jar; a schema that
 * needs anything else is a 400. Local references ({@code #/$defs/...}) are unaffected.
 *
 * <p><b>Errors never quote the output.</b> networknt's messages and instance locations can contain parts of the
 * validated document (an unexpected property name, an array index path under a model-chosen key). The error strings
 * returned by {@link #validate} are built only from the schema side: the keyword and its evaluation path in the
 * schema, plus the missing property for {@code required} (a name taken from the schema). A parse failure is reported
 * without the parser's message, which quotes the input.
 */
@Component
public class NetworkntOutputValidator implements OutputValidator {

    /** The meta-schemas bundled in the validator jar, which its own mapper resolves the json-schema.org IRIs to. */
    private static final Pattern BUNDLED_META_SCHEMA = Pattern.compile("^classpath:draft(-0[467])?/.*");

    /** At most this many meta-schema errors in a 400 message. */
    private static final int MAX_SCHEMA_ERRORS = 3;

    private final ObjectReader reader;
    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
    private final Map<VersionFlag, JsonSchema> metaSchemas = new ConcurrentHashMap<>();

    public NetworkntOutputValidator(ObjectMapper json) {
        // Trailing text after the JSON value ("{...} hope this helps") is not valid output.
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        SchemaLoader bundledOnly = NetworkntOutputValidator::refuseUnbundled;
        this.factory = JsonSchemaFactory.getInstance(VersionFlag.V202012,
                b -> b.schemaLoaders(loaders -> loaders.add(bundledOnly)));
    }

    /** Returning null lets the default loaders (classpath) serve a bundled meta-schema; anything else is refused. */
    private static InputStreamSource refuseUnbundled(com.networknt.schema.AbsoluteIri iri) {
        if (BUNDLED_META_SCHEMA.matcher(iri.toString()).matches()) return null;
        throw new IllegalArgumentException("references outside the schema are not supported: " + iri);
    }

    @Override
    public void checkSchema(String schemaJson) throws GatewayException {
        JsonNode node;
        try {
            node = reader.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            throw invalidSchema("it is not valid JSON");
        }
        if (node == null || !node.isObject()) throw invalidSchema("it must be a JSON object");

        VersionFlag draft = SpecVersionDetector.detectOptionalVersion(node, false).orElse(VersionFlag.V202012);
        List<String> metaErrors = new ArrayList<>();
        for (ValidationMessage m : metaSchema(draft).validate(node)) {
            if (metaErrors.size() == MAX_SCHEMA_ERRORS) break;
            metaErrors.add(m.getMessage());
        }
        if (!metaErrors.isEmpty()) throw invalidSchema(String.join("; ", metaErrors));
        try {
            compile(node);
        } catch (RuntimeException e) {
            throw invalidSchema(rootMessage(e));
        }
    }

    @Override
    public List<String> validate(String schemaJson, String output) {
        JsonSchema schema;
        try {
            schema = compile(reader.readTree(schemaJson));
        } catch (JsonProcessingException | RuntimeException e) {
            // Unreachable after RequestValidationFilter checked the schema; the caller's fault, not the model's.
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "'response_format.json_schema.schema' is not a valid"
                    + " JSON schema", e);
        }
        JsonNode document;
        try {
            document = reader.readTree(output);
        } catch (JsonProcessingException e) {
            return List.of("output is not valid JSON");
        }
        if (document == null || document.isMissingNode()) return List.of("output is empty");
        List<String> errors = new ArrayList<>();
        for (ValidationMessage m : schema.validate(document)) errors.add(describe(m));
        return errors;
    }

    /** One error from schema-side data only (see the class comment). */
    static String describe(ValidationMessage m) {
        String keyword = m.getType();
        String at = m.getEvaluationPath() == null ? "" : m.getEvaluationPath().toString();
        if (at.isEmpty()) at = "/";
        if ("required".equals(keyword) && m.getProperty() != null) {
            return "missing required property '" + m.getProperty() + "' (" + at + ")";
        }
        return "'" + keyword + "' failed at " + at;
    }

    private JsonSchema compile(JsonNode schemaNode) {
        JsonSchema schema = factory.getSchema(schemaNode, config);
        schema.initializeValidators(); // surfaces a bad regex or an unresolvable $ref now, not mid-validation
        return schema;
    }

    private JsonSchema metaSchema(VersionFlag draft) {
        return metaSchemas.computeIfAbsent(draft, d -> factory.getSchema(SchemaLocation.of(d.getId()), config));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        return message.lines().findFirst().orElse(message);
    }

    private static GatewayException invalidSchema(String reason) {
        String message = "'response_format.json_schema.schema' is not a valid JSON schema: " + reason;
        if (message.length() > 300) message = message.substring(0, 297) + "...";
        return new GatewayException(ErrorCode.INVALID_REQUEST, message);
    }
}
