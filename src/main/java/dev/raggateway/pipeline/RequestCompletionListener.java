package dev.raggateway.pipeline;

import dev.raggateway.core.RequestContext;

/**
 * Called once per request after it has finished and the response has been fully written and closed,
 * so work here is off the client's latency path (DECISIONS.md C7). Implementations: request log
 * writer (t01), request metrics (t04). Must not throw; the pipeline catches and counts failures anyway.
 */
public interface RequestCompletionListener {

    void onRequestFinished(RequestContext ctx);
}
