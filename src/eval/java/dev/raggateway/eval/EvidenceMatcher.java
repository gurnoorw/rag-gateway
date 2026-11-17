package dev.raggateway.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a retrieved chunk is relevant to a question (t13, DECISIONS.md D-53). Gold labels are a
 * document id plus a passage copied verbatim from that document's cleaned text, so one label works at every chunk
 * size. A chunk is relevant to a label when it belongs to the label's document and either
 * <ul>
 *   <li>contains the whole passage verbatim, or</li>
 *   <li>contains at least {@value #MIN_PERCENT}% of the passage's words in order: the longest run of consecutive
 *       passage words that also appears as consecutive words of the chunk, divided by the passage's word count.</li>
 * </ul>
 * The second rule is for a passage cut by a chunk boundary: the chunk then holds the passage's start or its end as
 * one unbroken run. A run is used rather than a scattered subsequence because a 1,000-word chunk contains most short
 * passages' common words ("the", "a", "is") in order somewhere, which would mark unrelated chunks relevant.
 *
 * <p>Words are lower-cased runs of letters and digits, so Markdown punctuation (backticks, list markers, links) and
 * line breaks do not affect the run. Matching never crosses documents: the right words in another document are not
 * relevant. Stateless and thread-safe.
 */
public final class EvidenceMatcher {

    /** The share of the passage's words, in order, that makes a chunk relevant. */
    public static final int MIN_PERCENT = 60;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private EvidenceMatcher() {
    }

    /** True when the chunk is relevant to at least one of the labels. */
    public static boolean relevant(String chunkDocId, String chunkText, Collection<EvalSet.Gold> gold) {
        for (EvalSet.Gold g : gold) {
            if (matches(chunkDocId, chunkText, g)) return true;
        }
        return false;
    }

    /** True when the chunk belongs to the label's document and holds its passage (whole, or 60% of its words). */
    public static boolean matches(String chunkDocId, String chunkText, EvalSet.Gold gold) {
        if (!gold.docId().equals(chunkDocId)) return false;
        if (chunkText.contains(gold.evidence())) return true;
        List<String> passage = words(gold.evidence());
        if (passage.isEmpty()) return false;
        int run = longestCommonRun(passage, words(chunkText));
        return run * 100L >= (long) MIN_PERCENT * passage.size();
    }

    /** Lower-cased runs of letters and digits, in order. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String w : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** Length of the longest run of consecutive words common to both lists (O(n·m) time, O(m) memory). */
    static int longestCommonRun(List<String> a, List<String> b) {
        int best = 0;
        int[] previous = new int[b.size() + 1];
        int[] current = new int[b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                current[j] = a.get(i - 1).equals(b.get(j - 1)) ? previous[j - 1] + 1 : 0;
                if (current[j] > best) best = current[j];
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return best;
    }
}
