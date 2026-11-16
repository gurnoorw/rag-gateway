package dev.raggateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkntOutputValidatorTest {

    static final String PERSON = """
            {"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer","minimum":0}},
             "required":["name","age"],"additionalProperties":false}""";

    final NetworkntOutputValidator validator = new NetworkntOutputValidator(new ObjectMapper());

    // ---- checkSchema (AC-t07-6, unit part) ----

    @Test
    void aWellFormedSchemaPasses() {
        assertThatCode(() -> validator.checkSchema(PERSON)).doesNotThrowAnyException();
    }

    @Test
    void localReferencesWork() {
        String schema = """
                {"$defs":{"n":{"type":"integer"}},"type":"object","properties":{"x":{"$ref":"#/$defs/n"}}}""";
        assertThatCode(() -> validator.checkSchema(schema)).doesNotThrowAnyException();
        assertThat(validator.validate(schema, "{\"x\":1}")).isEmpty();
        assertThat(validator.validate(schema, "{\"x\":\"one\"}")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":5}",                         // compiles in networknt, but rejects every output
            "{\"type\":\"objekt\"}",
            "{\"required\":\"name\"}",
            "{\"properties\":[]}",
            "{\"pattern\":\"[\"}",                  // bad regex: only compiling finds it
            "{\"$ref\":\"#/$defs/missing\"}",       // unresolvable local reference
            "{\"type\":\"object\"",                 // not JSON
            "[1,2]",                                // not an object
    })
    void ac_t07_6_malformedSchemaIs400(String schema) {
        assertThatThrownBy(() -> validator.checkSchema(schema))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessageStartingWith("'response_format.json_schema.schema' is not a valid JSON schema");
    }

    @Test
    void anOlderDraftNamedInDollarSchemaIsHonoured() {
        // "definitions" is draft-07's keyword; dependencies with an array value is valid in draft-07.
        String draft7 = """
                {"$schema":"http://json-schema.org/draft-07/schema#","type":"object",
                 "definitions":{"n":{"type":"integer"}},"properties":{"x":{"$ref":"#/definitions/n"}}}""";
        assertThatCode(() -> validator.checkSchema(draft7)).doesNotThrowAnyException();
        assertThat(validator.validate(draft7, "{\"x\":\"no\"}")).hasSize(1);
    }

    @Test
    void anUnknownMetaSchemaIs400() {
        assertThatThrownBy(() -> validator.checkSchema("{\"$schema\":\"https://example.invalid/my-draft\"}"))
                .isInstanceOf(GatewayException.class);
    }

    /** The schema is the caller's: a remote reference must never make the gateway fetch a URL. */
    @Test
    void remoteReferencesAreRefusedWithoutAnyFetch() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"type\":\"string\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String schema : List.of(
                    "{\"$ref\":\"" + base + "/s.json\"}",
                    "{\"$id\":\"" + base + "/root.json\",\"$ref\":\"other.json\"}",
                    "{\"$schema\":\"" + base + "/meta.json\"}",
                    "{\"$ref\":\"classpath:application.yml\"}")) {
                assertThatThrownBy(() -> validator.checkSchema(schema)).isInstanceOf(GatewayException.class)
                        .satisfies(e -> assertThat(((GatewayException) e).code()).isEqualTo(ErrorCode.INVALID_REQUEST));
            }
            assertThat(hits).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void schemaErrorMessageIsBounded() {
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 20; i++) many.append("\"p").append(i).append("\":{},");
        many.append("\"type\":5,\"required\":\"x\",\"minimum\":\"y\",\"maxLength\":\"z\"}");
        assertThatThrownBy(() -> validator.checkSchema(many.toString()))
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(300));
    }

    // ---- validate ----

    @Test
    void validOutputHasNoErrors() {
        assertThat(validator.validate(PERSON, "{\"name\":\"Ada\",\"age\":36}")).isEmpty();
        assertThat(validator.validate(PERSON, "  {\"name\":\"Ada\",\"age\":36}\n")).isEmpty();
    }

    @Test
    void schemaViolationsAreReportedFromTheSchemaSide() {
        List<String> errors = validator.validate(PERSON, "{\"name\":\"Ada\",\"age\":-1}");
        assertThat(errors).containsExactly("'minimum' failed at /properties/age/minimum");

        assertThat(validator.validate(PERSON, "{\"name\":\"Ada\"}"))
                .containsExactly("missing required property 'age' (/required)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json at all", "{\"name\":\"Ada\",\"age\":36} trailing words",
            "```json\n{\"name\":\"Ada\",\"age\":36}\n```", "{\"name\":"})
    void outputThatIsNotExactlyOneJsonValueIsInvalid(String output) {
        assertThat(validator.validate(PERSON, output)).containsExactly("output is not valid JSON");
    }

    @Test
    void emptyOutputIsInvalid() {
        assertThat(validator.validate(PERSON, "")).containsExactly("output is empty");
        assertThat(validator.validate(PERSON, "   ")).containsExactly("output is empty");
    }

    /** The 502 summary is built from these strings, so none of them may quote the model's output. */
    @Test
    void errorsNeverQuoteTheOutput() {
        String schema = """
                {"type":"object","properties":{"c":{"enum":["x","y"]},"d":{"const":"k"},"e":{"pattern":"^z$"},
                 "f":{"type":"array","items":{"type":"integer"}},"g":{"format":"email","maxLength":3}},
                 "patternProperties":{"^p":{"type":"string"}},"additionalProperties":false}""";
        String output = """
                {"c":"LEAK1","d":"LEAK2","e":"LEAK3","f":["LEAK4"],"g":"LEAK5@LEAK6","pLEAK7":1,"LEAK8":true}""";

        List<String> errors = validator.validate(schema, output);

        assertThat(errors).hasSizeGreaterThanOrEqualTo(6);
        assertThat(errors).allSatisfy(e -> assertThat(e).doesNotContain("LEAK"));
        assertThat(validator.validate(PERSON, "{\"LEAK\":")).allSatisfy(e -> assertThat(e).doesNotContain("LEAK"));
    }
}
