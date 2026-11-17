package dev.raggateway.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.raggateway.rag.query.RagAnswer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

/**
 * Non-streaming Messages API response bodies for WireMock (shapes from the Messages API reference; ids are fake),
 * and test data for the judge tests. Prices and numbers here are test data, not measurements.
 */
final class JudgeStubs {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Obviously fake key created by the test. */
    static final String FAKE_KEY = "test-judge-key-not-real";

    private JudgeStubs() {
    }

    /** A response with an empty thinking block first (display omitted), then one text block. */
    static String thinkingThenText(String text, long in, long out) {
        return message(List.of(thinking(), textBlock(text)), "end_turn", in, out);
    }

    static String textOnly(String text, long in, long out) {
        return message(List.of(textBlock(text)), "end_turn", in, out);
    }

    static String message(List<ObjectNode> content, String stopReason, long in, long out) {
        ObjectNode m = JSON.createObjectNode();
        m.put("id", "msg_test_judge");
        m.put("type", "message");
        m.put("role", "assistant");
        m.put("model", "claude-haiku-5-5");
        ArrayNode c = m.putArray("content");
        content.forEach(c::add);
        m.put("stop_reason", stopReason);
        m.putNull("stop_sequence");
        ObjectNode usage = m.putObject("usage");
        usage.put("input_tokens", in);
        usage.put("output_tokens", out);
        return m.toString();
    }

    static ObjectNode thinking() {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "thinking");
        b.put("thinking", "");
        b.put("signature", "c2lnbmF0dXJl");
        return b;
    }

    static ObjectNode textBlock(String text) {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "text");
        b.put("text", text);
        return b;
    }

    static String verdict(int correctness, boolean grounded) {
        return "{\"correctness\":" + correctness + ",\"grounded\":" + grounded + ",\"reason\":\"test verdict\"}";
    }

    static ResponseDefinitionBuilder ok(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** Settings with test prices (not list prices) so cap arithmetic is easy to read. */
    static JudgeSettings settings(String capUsd) {
        return new JudgeSettings("claude-haiku-5-5", new BigDecimal("1.00"), new BigDecimal("10.00"),
                "test prices, not list prices", "low", 1000, new BigDecimal(capUsd), JudgeSettings.API_KEY_ENV);
    }

    static RagAnswer answer(String text, boolean noAnswer, List<String> citedLabels, int sources) {
        List<RagAnswer.Source> src = new ArrayList<>();
        for (int i = 1; i <= sources; i++) {
            src.add(new RagAnswer.Source("S" + i, "00000000-0000-0000-0000-00000000000" + i, "doc." + i, "Title " + i,
                    0.01, i, 0));
        }
        List<RagAnswer.Citation> cites = new ArrayList<>();
        for (String label : citedLabels) {
            RagAnswer.Source s = src.get(Integer.parseInt(label.substring(1)) - 1);
            cites.add(new RagAnswer.Citation(s.source(), s.chunkId(), s.docId(), s.title()));
        }
        return new RagAnswer(text, cites, src, noAnswer, "hybrid", noAnswer ? null : "claude-test", null, "req-test");
    }
}
