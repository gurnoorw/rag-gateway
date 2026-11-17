package dev.raggateway.evalcontract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.validation.NetworkntOutputValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The t13 contracts (eval/results.schema.json, eval/questions.schema.json; D-53) are valid JSON schemas, accept
 * minimal well-formed documents and reject the mistakes they exist to catch. The documents below are test data:
 * their numbers are not measurements and never go in the README.
 */
class EvalSchemasTest {

    static final Path RESULTS = Path.of("eval/results.schema.json");
    static final Path QUESTIONS = Path.of("eval/questions.schema.json");
    final NetworkntOutputValidator validator = new NetworkntOutputValidator(new ObjectMapper());

    static final String KEYWORD_RUN = """
            {"run_id":"20000101T0000Z-test-data","measured_on":"test data, not a measurement","date":"2000-01-01",
             "git_commit":"0000000",
             "corpus":{"repo":"kubernetes/website","commit":"adaa04d017c75981660828f869b364a266b0ef53","documents":243,
                       "eval_set":"questions.v1","questions":38},
             "embedder":{"kind":"none"},
             "retrieval":[{"mode":"keyword","chunk_target_tokens":400,"questions":23,"recall_at_1":0,"recall_at_5":0,
                           "recall_at_10":0,"mrr":0,"ndcg_at_10":0}]}
            """;

    @Test
    void resultsSchemaAcceptsAKeywordOnlyRun() throws Exception {
        String schema = Files.readString(RESULTS);
        validator.checkSchema(schema);
        assertThat(validator.validate(schema, KEYWORD_RUN)).isEmpty();
    }

    @Test
    void resultsSchemaRejectsFakeEmbeddingsAndVectorRowsWithoutAModel() throws Exception {
        String schema = Files.readString(RESULTS);
        // the fake embedder never reaches a committed results file (D-18, D-53)
        assertThat(validator.validate(schema, KEYWORD_RUN.replace("\"kind\":\"none\"", "\"kind\":\"fake\""))).isNotEmpty();
        // a vector row needs a real embedder
        assertThat(validator.validate(schema, KEYWORD_RUN.replace("\"mode\":\"keyword\"", "\"mode\":\"vector\"")))
                .isNotEmpty();
        assertThat(validator.validate(schema, KEYWORD_RUN.replace("\"mode\":\"keyword\"", "\"mode\":\"vector\"")
                .replace("\"kind\":\"none\"", "\"kind\":\"nomic-embed-text\""))).isEmpty();
        // no hardware label (C14); a fraction above 1; an unknown chunk size
        assertThat(validator.validate(schema, KEYWORD_RUN.replace("\"measured_on\":\"test data, not a measurement\",", "")))
                .isNotEmpty();
        assertThat(validator.validate(schema, KEYWORD_RUN.replace("\"mrr\":0", "\"mrr\":1.5"))).isNotEmpty();
        assertThat(validator.validate(schema, KEYWORD_RUN.replace("\"chunk_target_tokens\":400", "\"chunk_target_tokens\":300")))
                .isNotEmpty();
    }

    @Test
    void questionSchemaEnforcesGoldLabelsByKind() throws Exception {
        String schema = Files.readString(QUESTIONS);
        validator.checkSchema(schema);
        String answerable = """
                {"id":"q01","kind":"exact_term","question":"What does maxSurge control in a rolling update?",
                 "expected":"answer","gold":[{"doc_id":"concepts.workloads.controllers.deployment",
                 "evidence":"maxSurge is an optional field that specifies the maximum number of Pods"}]}""";
        String unanswerable = """
                {"id":"q30","kind":"unanswerable","question":"What is the airspeed of an unladen swallow?",
                 "expected":"refusal","gold":[]}""";
        String acl = """
                {"id":"q40","kind":"acl","question":"Which kubeadm phase writes the certificates?","expected":"answer",
                 "required_groups":["finance"],"gold":[{"doc_id":"setup.production-environment.tools.kubeadm",
                 "evidence":"placeholder evidence passage for the schema test only"}]}""";
        assertThat(validator.validate(schema, answerable)).isEmpty();
        assertThat(validator.validate(schema, unanswerable)).isEmpty();
        assertThat(validator.validate(schema, acl)).isEmpty();
        // an answerable question without gold; an unanswerable one expecting an answer; an acl one without groups
        assertThat(validator.validate(schema, answerable.replaceAll("(?s)\"gold\":\\[.*\\]", "\"gold\":[]"))).isNotEmpty();
        assertThat(validator.validate(schema, unanswerable.replace("\"refusal\"", "\"answer\""))).isNotEmpty();
        assertThat(validator.validate(schema, acl.replace("\"required_groups\":[\"finance\"],", ""))).isNotEmpty();
    }
}
