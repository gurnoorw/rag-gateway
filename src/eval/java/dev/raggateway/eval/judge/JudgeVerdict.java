package dev.raggateway.eval.judge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The judge's structured verdict for one answer (D-53, rubric in {@code eval/judge/rubric.md}).
 *
 * <p>The class is the structured-output type: {@code outputConfig(JudgeVerdict.class)} makes the SDK derive the
 * request's JSON schema from it. That schema can only say "integer" and "boolean", so the response is checked
 * again against {@link #SCHEMA}, which also bounds the values ({@code correctness} in {0, 1, 2}, a non-empty
 * short reason). A verdict that fails it is rejected and reported, never counted.
 *
 * @param correctness 0 wrong or missing, 1 partly correct, 2 correct, against the gold evidence
 * @param grounded    true when every claim in the answer is supported by the cited source text
 * @param reason      one or two sentences explaining the scores
 */
public record JudgeVerdict(
        @JsonProperty("correctness")
        @JsonPropertyDescription("0 = wrong, missing or a refusal; 1 = partly correct or incomplete; 2 = correct and"
                + " complete, judged against the gold evidence")
        int correctness,
        @JsonProperty("grounded")
        @JsonPropertyDescription("true only if every claim in the answer is supported by the text of the sources it"
                + " cites")
        boolean grounded,
        @JsonProperty("reason")
        @JsonPropertyDescription("One or two sentences explaining both scores")
        String reason) {

    /** The schema every verdict must pass before it is counted. Draft 2020-12, no external references. */
    public static final String SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "required": ["correctness", "grounded", "reason"],
              "additionalProperties": false,
              "properties": {
                "correctness": {"type": "integer", "enum": [0, 1, 2]},
                "grounded": {"type": "boolean"},
                "reason": {"type": "string", "minLength": 1, "maxLength": 1000}
              }
            }""";
}
