package dev.raggateway.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command line end to end on Postgres (AC-t13-3, AC-t13-4): one command gives a row per mode asked and chunk
 * size, and the flags that keep fake or unlabelled numbers out of {@code eval/results/} are enforced before any work.
 * The source collection is synthetic (each gold passage of the committed eval set in a small document of that id), so
 * the numbers written are test data under {@code build/eval/}, never results.
 */
class EvalMainTest extends AbstractPostgresTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path REPO = Path.of("").toAbsolutePath();
    static final String LABEL = "test data, not a measurement";

    final String source = "t-evalmain-src-" + UUID.randomUUID();
    final String target = "t-evalmain-" + UUID.randomUUID();
    final List<Path> written = new ArrayList<>();

    @BeforeEach
    void loadSyntheticSource() {
        TestData.insertTenant(jdbc, source, 1000, BigDecimal.TEN, Set.of());
        EvalSet set = EvalSet.load(EvalMain.QUESTIONS, EvalSet.SCHEMA);
        Set<String> done = new HashSet<>();
        for (EvalSet.Question q : set.questions()) {
            for (EvalSet.Gold g : q.gold()) {
                if (!done.add(g.docId())) continue;
                Set<String> groups = g.docId().startsWith("setup.") ? Set.of("eng", "finance")
                        : g.docId().startsWith("tutorials.") ? Set.of("eng", "support") : Set.of("eng");
                RagFixtures.document(jdbc, source, "k8s-docs", g.docId(), g.docId(), groups, new FakeEmbedder(),
                        "# " + g.docId(), g.evidence(), "A filler paragraph that is not evidence.");
            }
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String t : List.of(source, target)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = ?").param(t).update();
        }
        for (Path p : written) Files.deleteIfExists(p);
    }

    String[] args(String out, String... more) {
        List<String> a = new ArrayList<>(List.of("retrieval", "--measured-on", LABEL, "--source-tenant", source,
                "--tenant", target, "--out", out));
        a.addAll(List.of(more));
        return a.toArray(String[]::new);
    }

    Path out(String name) {
        Path p = REPO.resolve("build/eval/test-" + UUID.randomUUID() + "-" + name + ".json");
        written.add(p);
        return p;
    }

    @Test
    void ac_t13_3_oneCommandWritesAKeywordRowPerChunkSizeAndTheAclSection() throws Exception {
        Path out = out("keyword");
        assertThat(EvalMain.run(args(REPO.relativize(out).toString()), REPO, dataSource)).isZero();
        JsonNode doc = JSON.readTree(Files.readString(out));
        assertThat(doc.path("embedder").path("kind").asText()).isEqualTo("none");
        assertThat(doc.path("measured_on").asText()).isEqualTo(LABEL);
        assertThat(doc.path("retrieval")).extracting(r -> r.path("mode").asText() + "@" + r.path("chunk_target_tokens").asInt())
                .containsExactly("keyword@200", "keyword@400", "keyword@800");
        assertThat(doc.path("retrieval")).allSatisfy(r -> assertThat(r.path("questions").asInt()).isEqualTo(28));
        assertThat(doc.path("acl").path("mode").asText()).isEqualTo("keyword");
        assertThat(doc.path("acl").path("unauthorised_leaked_chunks").asInt()).isZero();
        assertThat(doc.path("corpus").path("eval_set").asText()).isEqualTo("questions.v1");
    }

    @Test
    void ac_t13_3_aFakeSmokeRunWritesEveryModeAtEverySizeStampedUnderBuildEval() throws Exception {
        Path out = out("fake");
        assertThat(EvalMain.run(args(REPO.relativize(out).toString(), "--modes", "keyword,vector,hybrid",
                "--embedder", "fake"), REPO, dataSource)).isZero();
        JsonNode doc = JSON.readTree(Files.readString(out));
        assertThat(doc.path("retrieval")).hasSize(9);
        assertThat(doc.path("retrieval")).allSatisfy(r ->
                assertThat(r.path("note").asText()).isEqualTo(EvalResultsWriter.FAKE_STAMP));
        assertThat(doc.path("acl").path("mode").asText()).isEqualTo("hybrid");
    }

    @Test
    void ac_t13_4_flagsAreCheckedBeforeAnyWork() {
        // a fake run aimed at eval/results/
        assertThatThrownBy(() -> EvalMain.run(args("eval/results/fake.json", "--embedder", "fake",
                "--modes", "hybrid"), REPO, dataSource)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("build/eval");
        // a fake run with no --out
        assertThatThrownBy(() -> EvalMain.run(new String[]{"retrieval", "--measured-on", LABEL, "--embedder", "fake"},
                REPO, dataSource)).hasMessageContaining("--out build/eval");
        // vector rows without a model
        assertThatThrownBy(() -> EvalMain.run(args("build/eval/x.json", "--modes", "keyword,vector"), REPO, dataSource))
                .hasMessageContaining("keyword rows only");
        // no hardware label
        assertThatThrownBy(() -> EvalMain.run(new String[]{"retrieval", "--modes", "keyword"}, REPO, dataSource))
                .hasMessageContaining("--measured-on is required");
        assertThat(REPO.resolve("eval/results/fake.json")).doesNotExist();
        assertThat(jdbc.sql("SELECT count(*) FROM documents WHERE tenant_id = ?").param(target)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aFakeSmokeRunDefaultsToItsOwnTenantSoItCannotOverwriteRealVectors() {
        var fake = EvalMain.Options.parse(new String[]{"--measured-on", "x", "--modes", "vector",
                "--embedder", "fake", "--out", "build/eval/smoke.json"});
        var keyword = EvalMain.Options.parse(new String[]{"--measured-on", "x"});
        assertThat(fake.tenant()).isEqualTo("eval-fake");
        assertThat(keyword.tenant()).isEqualTo("eval");
    }
}
