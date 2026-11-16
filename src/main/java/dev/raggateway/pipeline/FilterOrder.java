package dev.raggateway.pipeline;

/**
 * Pipeline order (DECISIONS.md C3). Authentication and syntactic body checks run at the HTTP edge
 * before the pipeline; the filters below run in ascending order; the {@link Terminal} runs last.
 *
 * <pre>
 * edge: auth (401) -&gt; body mapping (400)
 *   -&gt; REQUEST_VALIDATION (400: max_tokens cap, JSON schema compiles)     t01, t07
 *   -&gt; MODEL_SELECTION    (resolve "auto", allowed models, 400/403)       t01, t06
 *   -&gt; CACHE              (hit answers here and never touches the limiter) t05
 *   -&gt; RATE_LIMIT         (budget check, token reservation, settle)      t02
 *   -&gt; Terminal           (output validation -&gt; fallback router -&gt; provider) t07, t03
 * </pre>
 */
public final class FilterOrder {

    public static final int REQUEST_VALIDATION = 100;
    public static final int MODEL_SELECTION = 200;
    public static final int CACHE = 300;
    public static final int RATE_LIMIT = 400;

    private FilterOrder() {
    }
}
