package dev.raggateway.eval;

import dev.raggateway.rag.query.RagAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Judge-free answer checks (D-53). Answers here are test data. */
class DeterministicChecksTest {

    static RagAnswer.Source source(int i) {
        return new RagAnswer.Source("S" + i, "chunk-" + i, "doc." + i, "Title", 0.01, i, 0);
    }

    static RagAnswer answer(String text, boolean noAnswer, List<RagAnswer.Citation> citations, int sources) {
        List<RagAnswer.Source> src = java.util.stream.IntStream.rangeClosed(1, sources).mapToObj(DeterministicChecksTest::source)
                .toList();
        return new RagAnswer(text, citations, src, noAnswer, "hybrid", null, null, "req");
    }

    static RagAnswer.Citation cite(String label, String chunk) {
        return new RagAnswer.Citation(label, chunk, "doc", "Title");
    }

    @Test
    void citationsMustNameAShownSourceWithTheSameChunk() {
        assertThat(DeterministicChecks.citationsWithinSources(answer("x", false, List.of(), 0))).isTrue();
        assertThat(DeterministicChecks.citationsWithinSources(
                answer("x [S1][S3]", false, List.of(cite("S1", "chunk-1"), cite("S3", "chunk-3")), 3))).isTrue();
        assertThat(DeterministicChecks.citationsWithinSources(
                answer("x [S4]", false, List.of(cite("S4", "chunk-4")), 3))).isFalse();
        assertThat(DeterministicChecks.citationsWithinSources(
                answer("x [S1]", false, List.of(cite("S1", "chunk-2")), 3))).isFalse();
    }

    @Test
    void refusalIsNoAnswerOrIDontKnowWithoutCitations() {
        assertThat(DeterministicChecks.isRefusal(answer(RagAnswer.NO_ANSWER_TEXT, true, List.of(), 0))).isTrue();
        assertThat(DeterministicChecks.isRefusal(answer("I don't know.", false, List.of(), 5))).isTrue();
        assertThat(DeterministicChecks.isRefusal(answer("  i DON’T know", false, List.of(), 5))).isTrue();
        assertThat(DeterministicChecks.isRefusal(
                answer("I don't know. The sources only cover Deployments.", false, List.of(), 5))).isTrue();

        assertThat(DeterministicChecks.isRefusal(answer("I don't know [S1].", false, List.of(cite("S1", "chunk-1")), 5)))
                .isFalse();
        assertThat(DeterministicChecks.isRefusal(answer("Pods restart; I don't know why.", false, List.of(), 5))).isFalse();
        assertThat(DeterministicChecks.isRefusal(answer("I don't knowledge", false, List.of(), 5))).isFalse();
        assertThat(DeterministicChecks.isRefusal(answer("The answer is 42.", false, List.of(), 5))).isFalse();
    }
}
