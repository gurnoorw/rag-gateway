package dev.raggateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.api.ChatRequestMapper;
import dev.raggateway.api.dto.ChatCompletionRequest;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.Role;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link CanonicalJsonCacheKeys}: what is in the key, what is not, and canonicalisation (AC-t05-3, AC-t05-11). */
class CacheKeysTest {

    private static final String TENANT = "tenant-a";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},"
            + "\"required\":[\"answer\"]}";
    private static final ChatRequest BASE = new ChatRequest("claude-haiku-5-5",
            List.of(new Message(Role.SYSTEM, "Be brief."), new Message(Role.USER, "What is Redis?")),
            256, 0.0, null, List.of("END"), false, false, null);

    private final CanonicalJsonCacheKeys keys = new CanonicalJsonCacheKeys();
    private final ObjectMapper json = new ObjectMapper();
    private final ChatRequestMapper mapper = new ChatRequestMapper(new GatewayProperties(1024, 8192, null, null));

    private String key(ChatRequest r) {
        return keys.keyFor(TENANT, r);
    }

    private static ChatRequest with(String model, List<Message> messages, int maxTokens, Double temperature,
                                    Double topP, List<String> stop, String schema) {
        return new ChatRequest(model, messages, maxTokens, temperature, topP, stop, false, false, schema);
    }

    @Test
    void keyIsVersionedPrefixPlusSha256HexAndDeterministic() {
        String k = key(BASE);
        assertThat(k).matches("cache:v1:[0-9a-f]{64}");
        assertThat(key(BASE)).isEqualTo(k);
        assertThat(new CanonicalJsonCacheKeys().keyFor(TENANT, BASE)).isEqualTo(k);
    }

    @Test
    void canonicalFormHoldsTheAnswerChangingFieldsInFixedOrder() {
        String canonical = new String(CanonicalJsonCacheKeys.canonicalJson(TENANT, BASE.withModel("m")), StandardCharsets.UTF_8);
        assertThat(canonical).isEqualTo("{\"tenant\":\"tenant-a\",\"model\":\"m\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"Be brief.\"},{\"role\":\"user\",\"content\":\"What is Redis?\"}],"
                + "\"temperature\":0,\"top_p\":null,\"max_tokens\":256,\"stop\":[\"END\"],\"schema\":null}");
    }

    // AC-t05-3 at key level: one test per field the plan names (model, a message, temperature, max tokens,
    // response format), plus the other fields the contract puts in the key.

    @Test
    void ac_t05_3_differentModelGivesDifferentKey() {
        assertThat(key(BASE.withModel("claude-sonnet-5-5"))).isNotEqualTo(key(BASE));
    }

    @Test
    void ac_t05_3_differentMessageContentGivesDifferentKey() {
        var changed = with(BASE.model(), List.of(BASE.messages().get(0), new Message(Role.USER, "What is Redis? ")),
                BASE.maxTokens(), BASE.temperature(), BASE.topP(), BASE.stop(), BASE.jsonSchema());
        assertThat(key(changed)).isNotEqualTo(key(BASE));
    }

    @Test
    void ac_t05_3_differentMessageRoleOrOrderGivesDifferentKey() {
        var asAssistant = with(BASE.model(), List.of(BASE.messages().get(0), new Message(Role.ASSISTANT, "What is Redis?")),
                BASE.maxTokens(), BASE.temperature(), BASE.topP(), BASE.stop(), BASE.jsonSchema());
        var u1 = new Message(Role.USER, "one");
        var u2 = new Message(Role.USER, "two");
        var inOrder = with(BASE.model(), List.of(u1, u2), 256, 0.0, null, List.of(), null);
        var swapped = with(BASE.model(), List.of(u2, u1), 256, 0.0, null, List.of(), null);
        var dropped = with(BASE.model(), List.of(u1), 256, 0.0, null, List.of(), null);

        assertThat(key(asAssistant)).isNotEqualTo(key(BASE));
        assertThat(Set.of(key(inOrder), key(swapped), key(dropped))).hasSize(3);
    }

    @Test
    void ac_t05_3_differentTemperatureGivesDifferentKey() {
        var warmer = with(BASE.model(), BASE.messages(), BASE.maxTokens(), 0.7, BASE.topP(), BASE.stop(), null);
        var absent = with(BASE.model(), BASE.messages(), BASE.maxTokens(), null, BASE.topP(), BASE.stop(), null);
        assertThat(Set.of(key(BASE), key(warmer), key(absent))).hasSize(3);
    }

    @Test
    void ac_t05_3_differentMaxTokensGivesDifferentKey() {
        var longer = with(BASE.model(), BASE.messages(), 257, BASE.temperature(), BASE.topP(), BASE.stop(), null);
        assertThat(key(longer)).isNotEqualTo(key(BASE));
    }

    @Test
    void ac_t05_3_differentResponseFormatGivesDifferentKey() {
        var withSchema = with(BASE.model(), BASE.messages(), BASE.maxTokens(), BASE.temperature(), BASE.topP(), BASE.stop(), SCHEMA);
        var otherSchema = with(BASE.model(), BASE.messages(), BASE.maxTokens(), BASE.temperature(), BASE.topP(), BASE.stop(),
                SCHEMA.replace("\"string\"", "\"number\""));
        assertThat(Set.of(key(BASE), key(withSchema), key(otherSchema))).hasSize(3);
    }

    @Test
    void differentTopPOrStopGivesDifferentKey() {
        var topP = with(BASE.model(), BASE.messages(), BASE.maxTokens(), BASE.temperature(), 0.9, BASE.stop(), null);
        var noStop = with(BASE.model(), BASE.messages(), BASE.maxTokens(), BASE.temperature(), BASE.topP(), List.of(), null);
        var otherStop = with(BASE.model(), BASE.messages(), BASE.maxTokens(), BASE.temperature(), BASE.topP(), List.of("STOP"), null);
        assertThat(Set.of(key(BASE), key(topP), key(noStop), key(otherStop))).hasSize(4);
    }

    @Test
    void tenantsNeverShareAKey() {
        assertThat(keys.keyFor("tenant-b", BASE)).isNotEqualTo(key(BASE));
    }

    @Test
    void streamAndIncludeUsageAreNotInTheKey() {
        var streamed = new ChatRequest(BASE.model(), BASE.messages(), BASE.maxTokens(), BASE.temperature(), BASE.topP(),
                BASE.stop(), true, true, BASE.jsonSchema());
        assertThat(key(streamed)).isEqualTo(key(BASE));
    }

    @Test
    void stringsCannotCollideAcrossFieldBoundaries() {
        var a = with("m", List.of(new Message(Role.USER, "a"), new Message(Role.USER, "b c")), 1, 0.0, null, List.of(), null);
        var b = with("m", List.of(new Message(Role.USER, "a b"), new Message(Role.USER, "c")), 1, 0.0, null, List.of(), null);
        var quoted = with("m", List.of(new Message(Role.USER, "a\"},{\"role\":\"user\",\"content\":\"b c")), 1, 0.0, null,
                List.of(), null);
        assertThat(Set.of(key(a), key(b), key(quoted))).hasSize(3);
    }

    @Test
    void zeroTemperatureSpellingsShareAKey() {
        var negativeZero = with(BASE.model(), BASE.messages(), BASE.maxTokens(), -0.0, BASE.topP(), BASE.stop(), null);
        assertThat(key(negativeZero)).isEqualTo(key(BASE));
    }

    @Test
    void ac_t05_11_requestBodiesThatDifferOnlyInKeyOrderAndWhitespaceGiveTheSameKey() throws Exception {
        String compact = """
                {"model":"claude-haiku-5-5","temperature":0,"max_tokens":256,"stop":["END"],\
                "messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"What is Redis?"}],\
                "response_format":{"type":"json_schema","json_schema":{"name":"a","schema":\
                {"type":"object","properties":{"answer":{"type":"string","minLength":1},"n":{"type":"integer"}},\
                "required":["answer"],"additionalProperties":false}}}}""";
        String reordered = """
                {
                  "messages": [
                    { "content": "Be brief.",      "role": "system" },
                    { "content": "What is Redis?", "role": "user" }
                  ],
                  "response_format": {
                    "json_schema": {
                      "schema": {
                        "additionalProperties": false,
                        "required": [ "answer" ],
                        "properties": {
                          "n":      { "type": "integer" },
                          "answer": { "minLength": 1.0, "type": "string" }
                        },
                        "type": "object"
                      },
                      "name": "a"
                    },
                    "type": "json_schema"
                  },
                  "stream": true,
                  "stop": [ "END" ],
                  "max_tokens": 256,
                  "temperature": 0.0,
                  "model": "claude-haiku-5-5"
                }
                """;

        String k1 = key(parse(compact));
        String k2 = key(parse(reordered));

        assertThat(k2).isEqualTo(k1);
        // ...and the schema's content still counts: one changed value is a different key.
        assertThat(key(parse(reordered.replace("\"integer\"", "\"number\"")))).isNotEqualTo(k1);
        // Array order inside a schema is significant JSON, so it is kept.
        assertThat(key(parse(compact.replace("\"required\":[\"answer\"]", "\"required\":[\"answer\",\"n\"]"))))
                .isNotEqualTo(key(parse(compact.replace("\"required\":[\"answer\"]", "\"required\":[\"n\",\"answer\"]"))));
    }

    @Test
    void anUnparseableSchemaStillGivesAStableKeyDistinctFromNoSchema() {
        var broken = with(BASE.model(), BASE.messages(), BASE.maxTokens(), BASE.temperature(), BASE.topP(), BASE.stop(), "{not json");
        assertThat(key(broken)).isEqualTo(key(broken)).isNotEqualTo(key(BASE));
    }

    @Test
    void concurrentCallersGetTheSameKey() {
        String expected = key(BASE);
        Set<String> seen = IntStream.range(0, 2_000).parallel().mapToObj(i -> key(BASE)).collect(Collectors.toSet());
        assertThat(seen).containsExactly(expected);
    }

    private ChatRequest parse(String body) throws Exception {
        return mapper.toChatRequest(json.readValue(body, ChatCompletionRequest.class));
    }
}
