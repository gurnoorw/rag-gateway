package dev.raggateway.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The committed eval set matches its schema and the composition D-53 asks for (AC-t13-1, the schema half; that every
 * passage is verbatim in its document is checked by scripts/eval/check_evidence.py against the fetched corpus).
 */
class EvalSetTest {

    static final Path V1 = Path.of("eval/questions.v1.jsonl");

    @Test
    void ac_t13_1_committedSetMatchesTheSchemaAndComposition() {
        EvalSet set = EvalSet.load(V1, EvalSet.SCHEMA);
        assertThat(set.name()).isEqualTo("questions.v1");
        assertThat(set.questions()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(set.ofKind(EvalSet.Kind.CONCEPTUAL)).hasSizeGreaterThanOrEqualTo(15);
        assertThat(set.ofKind(EvalSet.Kind.EXACT_TERM)).hasSizeGreaterThanOrEqualTo(8);
        assertThat(set.ofKind(EvalSet.Kind.UNANSWERABLE)).hasSizeGreaterThanOrEqualTo(5);
        assertThat(set.ofKind(EvalSet.Kind.ACL)).hasSizeGreaterThanOrEqualTo(6);
        assertThat(set.answerable()).allSatisfy(q -> assertThat(q.gold()).isNotEmpty());
        assertThat(set.ofKind(EvalSet.Kind.UNANSWERABLE)).allSatisfy(q -> assertThat(q.gold()).isEmpty());
        for (EvalSet.Question q : set.questions()) {
            for (EvalSet.Gold g : q.gold()) {
                assertThat(g.evidence().length()).as(q.id()).isBetween(20, 400);
                // a paraphrase, not a copy: the question never contains its passage
                assertThat(q.question()).as(q.id()).doesNotContain(g.evidence());
            }
        }
    }

    @Test
    void aclQuestionsNameDocumentsTheSupportKeyCannotSee() {
        // setup is eng,finance and concepts is eng (data/corpus-manifest.tsv); support sees only tutorials
        for (EvalSet.Question q : EvalSet.load(V1, EvalSet.SCHEMA).ofKind(EvalSet.Kind.ACL)) {
            assertThat(q.goldDocIds()).as(q.id()).allSatisfy(doc -> assertThat(doc).matches("(setup|concepts)\\..+"));
            assertThat(q.requiredGroups()).as(q.id()).isNotEmpty().doesNotContain("support");
            for (String doc : q.goldDocIds()) {
                Set<String> visibleTo = doc.startsWith("setup.") ? Set.of("eng", "finance") : Set.of("eng");
                assertThat(visibleTo).as(q.id()).containsAnyElementsOf(q.requiredGroups());
            }
        }
    }

    @Test
    void aLineThatBreaksTheSchemaFailsTheLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("questions.v9.jsonl");
        Files.writeString(file, """
                {"id":"q01","kind":"conceptual","question":"A question without gold labels?","expected":"answer","gold":[]}
                """);
        assertThatThrownBy(() -> EvalSet.load(file, EvalSet.SCHEMA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 1");
        assertThatThrownBy(() -> EvalSet.load(dir.resolve("questions.jsonl"), EvalSet.SCHEMA))
                .hasMessageContaining("questions.v<N>.jsonl");
    }
}
