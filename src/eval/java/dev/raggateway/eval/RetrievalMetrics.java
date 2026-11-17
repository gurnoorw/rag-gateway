package dev.raggateway.eval;

import java.util.List;

/**
 * Retrieval metrics over answerable questions (t13, DECISIONS.md D-53). Pure functions of the rankings, so they are
 * unit-tested on hand-made rankings (AC-t13-2).
 * <ul>
 *   <li><b>recall@k</b>: the share of questions with at least one relevant chunk in the top k. "Any hit", not the
 *       share of relevant chunks found: a smaller chunk size makes more chunks relevant and would otherwise be
 *       penalised for it.</li>
 *   <li><b>MRR</b>: the mean of 1 / rank of the first relevant chunk, 0 when none is in the top {@value #DEPTH}.</li>
 *   <li><b>nDCG@10</b>: binary relevance, DCG = sum of rel_i / log2(i + 1) over ranks i = 1..10, divided by the
 *       ideal DCG over min(relevant chunks in the collection, 10). It depends on how many chunks match, so it is
 *       compared within a chunk size, not across sizes. A question with no relevant chunk in the collection scores
 *       0.</li>
 * </ul>
 * Values are rounded to 6 decimals, so a results file does not carry floating-point noise.
 */
public final class RetrievalMetrics {

    /** Ranks scored: the top 10. */
    public static final int DEPTH = 10;

    private RetrievalMetrics() {
    }

    /**
     * One question's ranking.
     *
     * @param questionId    for reports
     * @param relevant      relevance of the retrieved chunks in rank order (rank 1 first); only the first
     *                      {@value #DEPTH} are scored
     * @param totalRelevant relevant chunks in the whole collection (the ideal ranking's size)
     */
    public record Ranking(String questionId, List<Boolean> relevant, int totalRelevant) {
        public Ranking {
            relevant = List.copyOf(relevant);
            if (totalRelevant < 0) throw new IllegalArgumentException("totalRelevant must be >= 0");
            long shown = relevant.stream().limit(DEPTH).filter(Boolean::booleanValue).count();
            if (shown > totalRelevant) {
                throw new IllegalArgumentException(questionId + ": " + shown + " relevant chunks retrieved but only "
                        + totalRelevant + " in the collection");
            }
        }

        /** 1-based rank of the first relevant chunk in the top {@value #DEPTH}, 0 when there is none. */
        public int firstRelevantRank() {
            for (int i = 0; i < Math.min(DEPTH, relevant.size()); i++) {
                if (relevant.get(i)) return i + 1;
            }
            return 0;
        }
    }

    /** The five metrics over a set of questions. */
    public record Summary(int questions, double recallAt1, double recallAt5, double recallAt10, double mrr,
                          double ndcgAt10) {
    }

    public static Summary of(List<Ranking> rankings) {
        if (rankings.isEmpty()) throw new IllegalArgumentException("no questions to score");
        double r1 = 0, r5 = 0, r10 = 0, mrr = 0, ndcg = 0;
        for (Ranking r : rankings) {
            r1 += hitAt(r, 1) ? 1 : 0;
            r5 += hitAt(r, 5) ? 1 : 0;
            r10 += hitAt(r, 10) ? 1 : 0;
            mrr += reciprocalRank(r);
            ndcg += ndcgAt10(r);
        }
        int n = rankings.size();
        return new Summary(n, round(r1 / n), round(r5 / n), round(r10 / n), round(mrr / n), round(ndcg / n));
    }

    /** At least one relevant chunk in the top {@code k}. */
    public static boolean hitAt(Ranking r, int k) {
        int first = r.firstRelevantRank();
        return first > 0 && first <= k;
    }

    public static double reciprocalRank(Ranking r) {
        int first = r.firstRelevantRank();
        return first == 0 ? 0 : 1.0 / first;
    }

    public static double ndcgAt10(Ranking r) {
        int ideal = Math.min(r.totalRelevant(), DEPTH);
        if (ideal == 0) return 0;
        double dcg = 0;
        for (int i = 0; i < Math.min(DEPTH, r.relevant().size()); i++) {
            if (r.relevant().get(i)) dcg += gain(i + 1);
        }
        double idcg = 0;
        for (int rank = 1; rank <= ideal; rank++) idcg += gain(rank);
        return dcg / idcg;
    }

    private static double gain(int rank) {
        return 1.0 / (Math.log(rank + 1) / Math.log(2));
    }

    static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
