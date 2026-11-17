package dev.raggateway.rag.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion (t10, DECISIONS.md D-49): a chunk's score is the sum of {@code 1 / (k + rank)} over the
 * lists it appears in, ranks 1-based. Only ranks are used, so a {@code ts_rank_cd} value never has to be compared
 * with a cosine distance (score normalisation was rejected: the scales are not comparable, and a min-max depends
 * on what else was retrieved).
 * <p>Order: higher score first; on an equal score, the better (smaller) single rank in either list; then the
 * smaller chunk id (uuid text, the same order as Postgres), so the result is stable. A single list (single-leg
 * modes) uses the same formula.
 */
public final class Rrf {

    /**
     * One fused entry.
     *
     * @param keywordRank 1-based rank in the keyword list, 0 when absent
     * @param vectorRank  1-based rank in the vector list, 0 when absent
     */
    public record Ranked(String chunkId, double score, int keywordRank, int vectorRank) {
        int bestRank() {
            if (keywordRank == 0) return vectorRank;
            if (vectorRank == 0) return keywordRank;
            return Math.min(keywordRank, vectorRank);
        }
    }

    static final Comparator<Ranked> ORDER = Comparator.comparingDouble(Ranked::score).reversed()
            .thenComparingInt(Ranked::bestRank)
            .thenComparing(Ranked::chunkId);

    private Rrf() {
    }

    /**
     * Fuses the two leg lists (either may be empty). Ids within one list are expected unique (they are primary
     * keys); a repeat keeps its first, better rank.
     */
    public static List<Ranked> fuse(int k, List<String> keywordIds, List<String> vectorIds) {
        if (k <= 0) throw new IllegalArgumentException("k must be > 0");
        Map<String, int[]> ranks = new LinkedHashMap<>();
        for (int i = 0; i < keywordIds.size(); i++) {
            int[] r = ranks.computeIfAbsent(keywordIds.get(i), id -> new int[2]);
            if (r[0] == 0) r[0] = i + 1;
        }
        for (int i = 0; i < vectorIds.size(); i++) {
            int[] r = ranks.computeIfAbsent(vectorIds.get(i), id -> new int[2]);
            if (r[1] == 0) r[1] = i + 1;
        }
        List<Ranked> out = new ArrayList<>(ranks.size());
        ranks.forEach((id, r) -> out.add(new Ranked(id, term(k, r[0]) + term(k, r[1]), r[0], r[1])));
        out.sort(ORDER);
        return out;
    }

    private static double term(int k, int rank) {
        return rank == 0 ? 0.0 : 1.0 / (k + rank);
    }
}
