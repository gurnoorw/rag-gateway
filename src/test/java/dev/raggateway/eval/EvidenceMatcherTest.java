package dev.raggateway.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link EvidenceMatcher}: the D-53 relevance rule (AC-t13-11). */
class EvidenceMatcherTest {

    static final String DOC = "concepts.workloads.pods.pod-termination";
    static final String PASSAGE = "The default `terminationGracePeriodSeconds` setting is 30 seconds.";
    static final EvalSet.Gold GOLD = new EvalSet.Gold(DOC, PASSAGE);

    /** A passage of exactly 100 distinct words, so percentages are word counts. */
    static final List<String> HUNDRED = IntStream.rangeClosed(1, 100).mapToObj(i -> "w" + i).toList();
    static final EvalSet.Gold LONG = new EvalSet.Gold(DOC, String.join(" ", HUNDRED));

    static String words(int from, int toExclusive) {
        return HUNDRED.subList(from, toExclusive).stream().collect(Collectors.joining(" "));
    }

    @Test
    void ac_t13_11_aChunkHoldingTheWholePassageIsRelevant() {
        String chunk = "## Termination\n\nWhen a Pod is deleted the kubelet runs the hook. " + PASSAGE + " Then it stops.";
        assertThat(EvidenceMatcher.matches(DOC, chunk, GOLD)).isTrue();
        // whitespace, case and Markdown differences still count as the whole passage (every word, in order)
        assertThat(EvidenceMatcher.matches(DOC, "the DEFAULT terminationGracePeriodSeconds\nsetting is 30 seconds", GOLD))
                .isTrue();
    }

    @Test
    void ac_t13_11_sixtyPercentOfTheWordsInOrderIsRelevant() {
        // a chunk boundary cut the passage: the chunk ends with its first 60 words
        String chunk = "previous text of the chunk " + words(0, 60);
        assertThat(EvidenceMatcher.matches(DOC, chunk, LONG)).isTrue();
        // or starts with its last 60
        assertThat(EvidenceMatcher.matches(DOC, words(40, 100) + " next section", LONG)).isTrue();
    }

    @Test
    void ac_t13_11_fiftyNinePercentIsNotRelevant() {
        assertThat(EvidenceMatcher.matches(DOC, "previous text " + words(0, 59), LONG)).isFalse();
        // 60 words that are present but not as one run (a gap after 30) are not "in order" either
        assertThat(EvidenceMatcher.matches(DOC, words(0, 30) + " unrelated " + words(30, 60), LONG)).isFalse();
    }

    @Test
    void ac_t13_11_theRightWordsInTheWrongDocumentAreNotRelevant() {
        String chunk = "Copied elsewhere: " + PASSAGE;
        assertThat(EvidenceMatcher.matches("concepts.workloads.pods.pod-lifecycle", chunk, GOLD)).isFalse();
        assertThat(EvidenceMatcher.relevant("concepts.workloads.pods.pod-lifecycle", chunk, List.of(GOLD))).isFalse();
    }

    @Test
    void relevantWhenAnyLabelMatches() {
        var other = new EvalSet.Gold("concepts.workloads.pods.probes", "Startup probes verify whether the application");
        assertThat(EvidenceMatcher.relevant(DOC, PASSAGE, List.of(other, GOLD))).isTrue();
        assertThat(EvidenceMatcher.relevant(DOC, "nothing in common here at all", List.of(other, GOLD))).isFalse();
    }

    @Test
    void wordsIgnorePunctuationAndCase() {
        assertThat(EvidenceMatcher.words("If you specify `--cascade=orphan` with `kubectl`, then"))
                .containsExactly("if", "you", "specify", "cascade", "orphan", "with", "kubectl", "then");
        assertThat(EvidenceMatcher.longestCommonRun(List.of("a", "b", "c", "d"), List.of("x", "b", "c", "y", "d")))
                .isEqualTo(2);
    }
}
