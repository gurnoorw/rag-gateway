package dev.raggateway.validation;

import dev.raggateway.error.GatewayException;

import java.util.List;

/** JSON-schema validation of model output (t07). Schemas are draft 2020-12 (D-10). */
public interface OutputValidator {

    /** Throws 400 {@code invalid_request} if the schema itself is not a valid JSON schema (AC-t07-6). */
    void checkSchema(String schemaJson) throws GatewayException;

    /**
     * Validate output text against a schema.
     *
     * @return human-readable errors; empty means valid. Output that is not JSON gives one error.
     */
    List<String> validate(String schemaJson, String output);
}
