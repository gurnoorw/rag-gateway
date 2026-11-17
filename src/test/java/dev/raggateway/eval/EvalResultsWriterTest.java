package dev.raggateway.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.raggateway.rag.RetrievalMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EvalResultsWriter}: committed runs are schema-checked before writing, and a fake-embedder run never reaches
 * {@code eval/results/} (AC-t13-4). The numbers below are test data, not measurements.
 */
class EvalResultsWriterTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String COMMIT = "adaa04d017c75981660828f869b364a266b0ef53";

    @TempDir
    Path repo;

    @BeforeEach
    void copySchema() throws Exception {
        Files.createDirectories(repo.resolve("eval"));
        Files.copy(EvalResultsWriter.SCHEMA, repo.resolve(EvalResultsWriter.SCHEMA));
    }

    static EvalResultsWriter.RunInfo info(EvalResultsWriter.Embedder embedder) {
        return new EvalResultsWriter.RunInfo("20000101T0000Z-test-data", "test data, not a measurement", "2000-01-01",
                "0000000", COMMIT, 243, "questions.v1", 39, embedder);
    }

    static RetrievalEvalRunner.Result row(RetrievalMode mode, EvalIndexer.ChunkSize size) {
        return new RetrievalEvalRunner.Result(mode, size, new RetrievalMetrics.Summary(28, 0.1, 0.2, 0.3, 0.15, 0.2),
                List.of());
    }

    static AclEvalRunner.Result acl(RetrievalMode mode) {
        return new AclEvalRunner.Result(mode, EvalIndexer.ChunkSize.C400, 6, 0, 0.0, 0.5, List.of());
    }

    static List<RetrievalEvalRunner.Result> keywordRows() {
        return List.of(row(RetrievalMode.KEYWORD, EvalIndexer.ChunkSize.C200),
                row(RetrievalMode.KEYWORD, EvalIndexer.ChunkSize.C400),
                row(RetrievalMode.KEYWORD, EvalIndexer.ChunkSize.C800));
    }

    @Test
    void aKeywordOnlyRunIsSchemaCheckedAndWritten() throws Exception {
        ObjectNode doc = EvalResultsWriter.build(info(EvalResultsWriter.Embedder.NONE), keywordRows(),
                acl(RetrievalMode.KEYWORD));
        Path out = EvalResultsWriter.write(doc, Path.of("eval/results/20000101T0000Z-test-data.json"), repo,
                EvalResultsWriter.SCHEMA);
        JsonNode written = JSON.readTree(Files.readString(out));
        assertThat(written.path("embedder").path("kind").asText()).isEqualTo("none");
        assertThat(written.path("retrieval")).hasSize(3);
        assertThat(written.path("acl").path("unauthorised_leaked_chunks").asInt()).isZero();
    }

    @Test
    void ac_t13_4_aFakeEmbedderRunRefusesToWriteUnderEvalResults() {
        ObjectNode doc = EvalResultsWriter.build(info(EvalResultsWriter.Embedder.FAKE),
                List.of(row(RetrievalMode.HYBRID, EvalIndexer.ChunkSize.C400)), acl(RetrievalMode.HYBRID));
        assertThatThrownBy(() -> EvalResultsWriter.write(doc, Path.of("eval/results/x.json"), repo,
                EvalResultsWriter.SCHEMA)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("build/eval");
        assertThatThrownBy(() -> EvalResultsWriter.write(doc, Path.of("build/eval/../../eval/results/x.json"), repo,
                EvalResultsWriter.SCHEMA)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.resolve("eval/results")).doesNotExist();
    }

    @Test
    void ac_t13_4_aFakeEmbedderRunIsStampedOnEveryRowUnderBuildEval() throws Exception {
        ObjectNode doc = EvalResultsWriter.build(info(EvalResultsWriter.Embedder.FAKE),
                List.of(row(RetrievalMode.VECTOR, EvalIndexer.ChunkSize.C200),
                        row(RetrievalMode.HYBRID, EvalIndexer.ChunkSize.C200)), acl(RetrievalMode.HYBRID));
        Path out = EvalResultsWriter.write(doc, Path.of("build/eval/smoke.json"), repo, EvalResultsWriter.SCHEMA);
        JsonNode written = JSON.readTree(Files.readString(out));
        assertThat(written.path("embedder").path("note").asText()).isEqualTo(EvalResultsWriter.FAKE_STAMP);
        assertThat(written.path("retrieval")).allSatisfy(r ->
                assertThat(r.path("note").asText()).isEqualTo("fake embedder, not a model"));
        assertThat(written.path("acl").path("note").asText()).isEqualTo(EvalResultsWriter.FAKE_STAMP);
    }

    @Test
    void ac_t13_4_resultsNameTheEmbedderAndAVectorRowWithoutAModelIsNotWritten() {
        // a keyword-only run claiming a hybrid row: the schema refuses it and nothing is written
        ObjectNode doc = EvalResultsWriter.build(info(EvalResultsWriter.Embedder.NONE),
                List.of(row(RetrievalMode.HYBRID, EvalIndexer.ChunkSize.C400)), null);
        assertThat(doc.path("embedder").path("kind").asText()).isEqualTo("none");
        assertThatThrownBy(() -> EvalResultsWriter.write(doc, Path.of("eval/results/x.json"), repo,
                EvalResultsWriter.SCHEMA)).isInstanceOf(IllegalStateException.class).hasMessageContaining("nothing written");
        assertThat(repo.resolve("eval/results/x.json")).doesNotExist();

        // with the real model named, the same row is accepted
        ObjectNode real = EvalResultsWriter.build(info(EvalResultsWriter.Embedder.NOMIC),
                List.of(row(RetrievalMode.HYBRID, EvalIndexer.ChunkSize.C400)), null);
        assertThat(EvalResultsWriter.write(real, Path.of("eval/results/y.json"), repo, EvalResultsWriter.SCHEMA))
                .exists();
    }

    @Test
    void measuredOnIsRequiredAndNamesTheRun() {
        assertThatThrownBy(() -> new EvalResultsWriter.RunInfo("20000101T0000Z-x", " ", "2000-01-01", "0000000",
                COMMIT, 1, "questions.v1", 30, EvalResultsWriter.Embedder.NONE))
                .hasMessageContaining("--measured-on");
        ZonedDateTime at = ZonedDateTime.of(2026, 11, 17, 4, 30, 0, 0, ZoneOffset.UTC);
        assertThat(EvalResultsWriter.runId(at, "cloud container, 4 vCPU / 15 GB", EvalResultsWriter.Embedder.NONE))
                .isEqualTo("20261117T0430Z-cloud-container-4-vcpu-15-gb");
        assertThat(EvalResultsWriter.runId(at, "cloud container, 4 vCPU / 15 GB", EvalResultsWriter.Embedder.FAKE))
                .endsWith("-fake");
    }
}
