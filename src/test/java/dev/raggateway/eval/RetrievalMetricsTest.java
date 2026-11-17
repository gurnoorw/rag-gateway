package dev.raggateway.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** recall@1/5/10, MRR and nDCG@10 against values computed by hand (AC-t13-2, D-53). */
class RetrievalMetricsTest {

    static final double EPS = 1e-6;

    /** Relevance flags for ten ranks, true at the given 1-based ranks. */
    static List<Boolean> relevantAt(int... ranks) {
        List<Boolean> out = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            boolean hit = false;
            for (int r : ranks) hit |= r == i;
            out.add(hit);
        }
        return out;
    }

    static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    // A: one relevant chunk in the collection, retrieved at rank 1
    final RetrievalMetrics.Ranking a = new RetrievalMetrics.Ranking("a", relevantAt(1), 1);
    // B: two relevant chunks in the collection, retrieved at ranks 3 and 7
    final RetrievalMetrics.Ranking b = new RetrievalMetrics.Ranking("b", relevantAt(3, 7), 2);
    // C: a relevant chunk exists but none is retrieved
    final RetrievalMetrics.Ranking c = new RetrievalMetrics.Ranking("c", relevantAt(), 1);
    // D: three relevant chunks in the collection, one retrieved at rank 6
    final RetrievalMetrics.Ranking d = new RetrievalMetrics.Ranking("d", relevantAt(6), 3);

    @Test
    void ac_t13_2_perQuestionValuesMatchHandComputation() {
        assertThat(RetrievalMetrics.reciprocalRank(a)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.ndcgAt10(a)).isEqualTo(1.0);

        // B: first hit at 3; DCG = 1/log2(4) + 1/log2(8) = 0.5 + 1/3; ideal over 2 = 1 + 1/log2(3)
        assertThat(RetrievalMetrics.hitAt(b, 1)).isFalse();
        assertThat(RetrievalMetrics.hitAt(b, 5)).isTrue();
        assertThat(RetrievalMetrics.reciprocalRank(b)).isCloseTo(1.0 / 3, within(EPS));
        assertThat(RetrievalMetrics.ndcgAt10(b)).isCloseTo((0.5 + 1.0 / 3) / (1 + 1 / log2(3)), within(EPS));
        assertThat(RetrievalMetrics.ndcgAt10(b)).isCloseTo(0.510956, within(EPS));

        // C: no relevant chunk retrieved: every metric 0
        assertThat(RetrievalMetrics.hitAt(c, 10)).isFalse();
        assertThat(RetrievalMetrics.reciprocalRank(c)).isZero();
        assertThat(RetrievalMetrics.ndcgAt10(c)).isZero();

        // D: hit at 6 only (missed at 5); ideal over min(3, 10) = 1 + 1/log2(3) + 1/log2(4)
        assertThat(RetrievalMetrics.hitAt(d, 5)).isFalse();
        assertThat(RetrievalMetrics.hitAt(d, 10)).isTrue();
        assertThat(RetrievalMetrics.reciprocalRank(d)).isCloseTo(1.0 / 6, within(EPS));
        assertThat(RetrievalMetrics.ndcgAt10(d)).isCloseTo((1 / log2(7)) / (1 + 1 / log2(3) + 0.5), within(EPS));
        assertThat(RetrievalMetrics.ndcgAt10(d)).isCloseTo(0.167160, within(EPS));
    }

    @Test
    void ac_t13_2_summaryMatchesHandComputation() {
        RetrievalMetrics.Summary s = RetrievalMetrics.of(List.of(a, b, c, d));
        assertThat(s.questions()).isEqualTo(4);
        assertThat(s.recallAt1()).isEqualTo(0.25);   // a
        assertThat(s.recallAt5()).isEqualTo(0.5);    // a, b
        assertThat(s.recallAt10()).isEqualTo(0.75);  // a, b, d
        assertThat(s.mrr()).isEqualTo(0.375);        // (1 + 1/3 + 0 + 1/6) / 4
        assertThat(s.ndcgAt10()).isCloseTo(0.419529, within(EPS));
    }

    @Test
    void ranksBeyondTenAreNotScoredAndIdealIsCappedAtTen() {
        List<Boolean> eleven = new ArrayList<>(relevantAt());
        eleven.add(true); // rank 11
        var late = new RetrievalMetrics.Ranking("late", eleven, 1);
        assertThat(RetrievalMetrics.reciprocalRank(late)).isZero();
        assertThat(RetrievalMetrics.hitAt(late, 10)).isFalse();

        // 25 relevant chunks in the collection, all ten retrieved: the ideal is ten hits, so nDCG is 1
        var all = new RetrievalMetrics.Ranking("all", relevantAt(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 25);
        assertThat(RetrievalMetrics.ndcgAt10(all)).isCloseTo(1.0, within(EPS));
    }

    @Test
    void aQuestionWithNoRelevantChunkInTheCollectionScoresZero() {
        var none = new RetrievalMetrics.Ranking("none", relevantAt(), 0);
        assertThat(RetrievalMetrics.ndcgAt10(none)).isZero();
        assertThatThrownBy(() -> new RetrievalMetrics.Ranking("bad", relevantAt(1, 2), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetrievalMetrics.of(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
