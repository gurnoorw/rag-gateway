package dev.raggateway.rag.query;

import dev.raggateway.rag.query.Rrf.Ranked;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Reciprocal rank fusion on fixed lists (AC-t10-1, D-49). */
class RrfTest {

    @Test
    void ac_t10_1_rankOneInBothListsScoresTwoOverSixtyOneAndInOneListOneOverSixtyOne() {
        List<Ranked> both = Rrf.fuse(60, List.of("a"), List.of("a"));
        assertThat(both).singleElement().satisfies(r -> {
            assertThat(r.score()).isEqualTo(2.0 / 61);
            assertThat(r.keywordRank()).isEqualTo(1);
            assertThat(r.vectorRank()).isEqualTo(1);
        });

        List<Ranked> one = Rrf.fuse(60, List.of("a"), List.of());
        assertThat(one).singleElement().satisfies(r -> {
            assertThat(r.score()).isEqualTo(1.0 / 61);
            assertThat(r.keywordRank()).isEqualTo(1);
            assertThat(r.vectorRank()).isZero();
        });
        assertThat(Rrf.fuse(60, List.of(), List.of("a")).getFirst().score()).isEqualTo(1.0 / 61);
    }

    @Test
    void ac_t10_1_orderMatchesAHandComputedList() {
        // k = 60, ranks 1-based:
        //   a: kw 1 + vec 2 = 1/61 + 1/62 = 0.032523
        //   c: kw 3 + vec 1 = 1/63 + 1/61 = 0.032266
        //   b: kw 2 + vec 4 = 1/62 + 1/64 = 0.031754
        //   e:        vec 3 = 1/63        = 0.015873
        //   d: kw 4         = 1/64        = 0.015625
        List<Ranked> fused = Rrf.fuse(60, List.of("a", "b", "c", "d"), List.of("c", "a", "e", "b"));

        assertThat(fused).extracting(Ranked::chunkId).containsExactly("a", "c", "b", "e", "d");
        assertThat(fused).extracting(Ranked::score).containsExactly(
                1.0 / 61 + 1.0 / 62, 1.0 / 63 + 1.0 / 61, 1.0 / 62 + 1.0 / 64, 1.0 / 63, 1.0 / 64);
        assertThat(fused.get(0).score()).isCloseTo(0.032523, within(1e-6));
        assertThat(fused).extracting(Ranked::keywordRank).containsExactly(1, 3, 2, 0, 4);
        assertThat(fused).extracting(Ranked::vectorRank).containsExactly(2, 1, 4, 3, 0);
    }

    @Test
    void ac_t10_1_tiesGoToTheBetterSingleRankThenTheSmallerChunkId() {
        // k = 1 makes exact ties easy to build: 1/(1+1) = 1/(1+3) + 1/(1+3) = 0.5.
        //   ccc: kw 1        -> 0.5, best rank 1
        //   bbb: vec 1       -> 0.5, best rank 1   (same score and best rank as ccc: smaller id first)
        //   aaa: kw 3, vec 3 -> 0.5, best rank 3   (smallest id, but a worse best rank: after both)
        //   ddd: kw 2        -> 1/3, best rank 2
        //   eee: vec 2       -> 1/3, best rank 2
        List<Ranked> fused = Rrf.fuse(1, List.of("ccc", "ddd", "aaa"), List.of("bbb", "eee", "aaa"));

        assertThat(fused).extracting(Ranked::chunkId).containsExactly("bbb", "ccc", "aaa", "ddd", "eee");
        assertThat(fused.subList(0, 3)).extracting(Ranked::score).containsOnly(0.5);
    }

    @Test
    void symmetricRanksTieExactlyAndFallBackToTheChunkId() {
        // (1, 2) and (2, 1): the same sum, the same best rank.
        List<Ranked> fused = Rrf.fuse(60, List.of("y", "x"), List.of("x", "y"));
        assertThat(fused.get(0).score()).isEqualTo(fused.get(1).score());
        assertThat(fused).extracting(Ranked::chunkId).containsExactly("x", "y");
    }

    @Test
    void emptyListsFuseToNothing() {
        assertThat(Rrf.fuse(60, List.of(), List.of())).isEmpty();
    }
}
