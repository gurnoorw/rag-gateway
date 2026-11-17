package dev.raggateway.rag;

/**
 * pgvector's text form ({@code [x,y,...]}), bound as a string with {@code CAST(? AS vector)} (D-47): no driver
 * dependency, and the column type still checks the length. Used by the chunk store (t09) and the vector search
 * leg (t10), so both write and query vectors the same way.
 */
public final class PgVector {

    private PgVector() {
    }

    /** {@link Float#toString} round-trips exactly and pgvector parses its exponent form. */
    public static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i])) throw new IllegalArgumentException("vector value " + i + " is not finite");
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
