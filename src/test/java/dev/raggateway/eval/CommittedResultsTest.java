package dev.raggateway.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.validation.NetworkntOutputValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every committed results file under {@code eval/results/} matches the results schema, is named after its run id,
 * comes from a real embedder or none (never the fake, D-18, D-53) and, when it has an ACL section, reports no leaked
 * chunk (AC-t13-10).
 */
class CommittedResultsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void committedResultsFilesMatchTheSchemaAndTheirNames() throws Exception {
        Path dir = EvalResultsWriter.RESULTS_DIR;
        if (!Files.isDirectory(dir)) return;
        String schema = Files.readString(EvalResultsWriter.SCHEMA);
        var validator = new NetworkntOutputValidator(JSON);
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            String text = Files.readString(file);
            assertThat(validator.validate(schema, text)).as(file.toString()).isEmpty();
            JsonNode doc = JSON.readTree(text);
            assertThat(doc.path("run_id").asText() + ".json").isEqualTo(file.getFileName().toString());
            assertThat(doc.path("embedder").path("kind").asText()).isIn("none", "nomic-embed-text");
            assertThat(text).doesNotContain(EvalResultsWriter.FAKE_STAMP);
            if (doc.has("acl")) assertThat(doc.path("acl").path("unauthorised_leaked_chunks").asInt()).isZero();
        }
    }
}
