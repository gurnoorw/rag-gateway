package dev.raggateway.rag.ingest;

import dev.raggateway.rag.Embedder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for {@code nomic-embed-text} where Ollama is not reachable (D-18, refined by D-47):
 * feature hashing of lower-cased word tokens into {@link #dimensions()} signed buckets, L2-normalised.
 * <ul>
 *   <li>The task prefix ({@code search_document: } / {@code search_query: }) is stripped first, so a document
 *       and a query with the same words get the same vector, as the real model's prefixes are meant to
 *       allow.</li>
 *   <li>Texts that share words share buckets, so their cosine is higher than for unrelated texts: the vector
 *       leg of retrieval returns plausible rows in tests (t10, t13). It is not a language model; every number
 *       produced with it is labelled "fake embedder, not a model".</li>
 *   <li>A text with no word tokens gets a fixed unit vector (bucket 0), never a zero vector, whose cosine
 *       distance is undefined.</li>
 * </ul>
 * Stateless and thread-safe. No I/O, so embedding holds nothing but CPU.
 */
public final class FakeEmbedder implements Embedder {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final int dimensions;

    public FakeEmbedder() {
        this(768);
    }

    public FakeEmbedder(int dimensions) {
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be > 0");
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(vector(t));
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        return vector(text);
    }

    /** The vector for a text, with or without a task prefix. */
    public float[] vector(String text) {
        String body = stripPrefix(text == null ? "" : text);
        float[] v = new float[dimensions];
        for (String word : NON_WORD.split(body.toLowerCase(Locale.ROOT))) {
            if (word.isEmpty()) continue;
            int h = mix(word.hashCode());
            int bucket = Math.floorMod(h, dimensions);
            v[bucket] += (mix(h ^ 0x5bd1e995) & 1) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) v[i] *= scale;
        return v;
    }

    static String stripPrefix(String text) {
        if (text.startsWith(DOCUMENT_PREFIX)) return text.substring(DOCUMENT_PREFIX.length());
        if (text.startsWith(QUERY_PREFIX)) return text.substring(QUERY_PREFIX.length());
        return text;
    }

    /** MurmurHash3's 32-bit finaliser: spreads {@link String#hashCode()} (stable by spec) over all bits. */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
