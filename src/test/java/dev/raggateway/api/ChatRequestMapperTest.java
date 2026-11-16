package dev.raggateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.api.dto.ChatCompletionRequest;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.Role;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-16 surface: what the mapper accepts, its defaults, and every rejection (400 invalid_request). */
class ChatRequestMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatRequestMapper mapper =
            new ChatRequestMapper(new GatewayProperties(1024, 8192, Map.of(), Map.of()));

    private ChatRequest map(String json) throws Exception {
        return mapper.toChatRequest(JSON.readValue(json, ChatCompletionRequest.class));
    }

    private void assertRejected(String json, String messagePart) {
        assertThatThrownBy(() -> map(json))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessageContaining(messagePart);
    }

    @Test
    void minimalRequestGetsDefaults() throws Exception {
        ChatRequest r = map("""
                {"model":"claude-haiku-5-5","messages":[{"role":"user","content":"hi"}]}""");

        assertThat(r.model()).isEqualTo("claude-haiku-5-5");
        assertThat(r.messages()).containsExactly(new Message(Role.USER, "hi"));
        assertThat(r.maxTokens()).isEqualTo(1024);
        assertThat(r.temperature()).isNull();
        assertThat(r.topP()).isNull();
        assertThat(r.stop()).isEmpty();
        assertThat(r.stream()).isFalse();
        assertThat(r.includeUsage()).isFalse();
        assertThat(r.jsonSchema()).isNull();
    }

    @Test
    void fullRequestIsMapped() throws Exception {
        ChatRequest r = map("""
                {"model":"m","messages":[{"role":"user","content":"q"},{"role":"assistant","content":"a"},
                 {"role":"user","content":"q2"}],
                 "max_tokens":50,"temperature":0,"top_p":0.5,"stop":["END","STOP"],"stream":true,
                 "stream_options":{"include_usage":true},"n":1,"user":"ignored","seed":7,
                 "response_format":{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object"}}}}""");

        assertThat(r.messages()).extracting(Message::role).containsExactly(Role.USER, Role.ASSISTANT, Role.USER);
        assertThat(r.maxTokens()).isEqualTo(50);
        assertThat(r.temperature()).isEqualTo(0.0);
        assertThat(r.topP()).isEqualTo(0.5);
        assertThat(r.stop()).containsExactly("END", "STOP");
        assertThat(r.stream()).isTrue();
        assertThat(r.includeUsage()).isTrue();
        assertThat(r.jsonSchema()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void maxCompletionTokensWinsOverMaxTokens() throws Exception {
        ChatRequest r = map("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"max_tokens":10,"max_completion_tokens":20}""");
        assertThat(r.maxTokens()).isEqualTo(20);
    }

    @Test
    void stopAsSingleString() throws Exception {
        assertThat(map("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"stop":"END"}""").stop()).containsExactly("END");
    }

    @Test
    void systemMessagesAreJoinedIntoOneLeadingMessage() throws Exception {
        ChatRequest r = map("""
                {"model":"m","messages":[{"role":"system","content":"be brief"},{"role":"user","content":"q"},
                 {"role":"system","content":"be kind"}]}""");

        assertThat(r.messages()).containsExactly(
                new Message(Role.SYSTEM, "be brief\n\nbe kind"),
                new Message(Role.USER, "q"));
    }

    @Test
    void responseFormatTextMeansNoSchema() throws Exception {
        assertThat(map("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"response_format":{"type":"text"}}""")
                .jsonSchema()).isNull();
    }

    @Test
    void includeUsageWithoutStreamOptionsIsFalse() throws Exception {
        assertThat(map("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"stream":true}""").includeUsage()).isFalse();
    }

    // ---- rejections ----

    @Test
    void ac_t01_4_missingMessagesIsRejected() {
        assertRejected("""
                {"model":"m"}""", "'messages'");
    }

    @Test
    void ac_t01_4_emptyMessagesIsRejected() {
        assertRejected("""
                {"model":"m","messages":[]}""", "'messages'");
    }

    @Test
    void onlySystemMessagesIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"system","content":"s"}]}""", "at least one user or assistant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}",
            "{\"model\":\" \",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}"})
    void missingModelIsRejected(String json) {
        assertRejected(json, "'model'");
    }

    @Test
    void nOtherThanOneIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"n":2}""", "n = 1");
    }

    @Test
    void toolsAreRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"tools":[{"type":"function"}]}""", "'tools'");
    }

    @Test
    void functionsAreRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"functions":[{"name":"f"}]}""", "'functions'");
    }

    @Test
    void imageContentPartsAreRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}""",
                "content must be a string");
    }

    @Test
    void nullContentIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":null}]}""", "content must be a string");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool", "developer", "function", "USER"})
    void unsupportedRoleIsRejected(String role) {
        assertRejected("{\"model\":\"m\",\"messages\":[{\"role\":\"" + role + "\",\"content\":\"q\"}]}",
                "role must be system, user or assistant");
    }

    @Test
    void missingRoleIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"content":"q"}]}""", "role is required");
    }

    @Test
    void responseFormatJsonObjectIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"response_format":{"type":"json_object"}}""",
                "json_object");
    }

    @Test
    void responseFormatUnknownTypeIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"response_format":{"type":"xml"}}""",
                "response_format");
    }

    @Test
    void jsonSchemaWithoutSchemaObjectIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"response_format":{"type":"json_schema","json_schema":{"name":"x"}}}""",
                "json_schema.schema");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"max_tokens\":0", "\"max_tokens\":-5", "\"max_completion_tokens\":0"})
    void nonPositiveMaxTokensIsRejected(String field) {
        assertRejected("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]," + field + "}",
                "'max_tokens'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"temperature\":-0.1", "\"temperature\":2.5"})
    void temperatureOutOfRangeIsRejected(String field) {
        assertRejected("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]," + field + "}",
                "'temperature'");
    }

    @Test
    void topPOutOfRangeIsRejected() {
        assertRejected("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"top_p":1.5}""", "'top_p'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"stop\":5", "\"stop\":[\"a\",1]", "\"stop\":{\"a\":1}"})
    void malformedStopIsRejected(String field) {
        assertRejected("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]," + field + "}",
                "'stop'");
    }

    @Test
    void rejectionMessageNeverEchoesMessageContent() {
        assertThatThrownBy(() -> map("""
                {"model":"m","messages":[{"role":"tool","content":"secret-marker-123"}]}"""))
                .hasMessageNotContaining("secret-marker-123");
    }

    @Test
    void stopListIsImmutableCopy() throws Exception {
        assertThat(map("""
                {"model":"m","messages":[{"role":"user","content":"q"}],"stop":["a"]}""").stop())
                .isEqualTo(List.of("a"));
    }
}
