package dev.raggateway.cache;

import dev.raggateway.core.ChatRequest;

/**
 * Turns a request into its cache key (t05 round prep; implementation by Dev A, used by Dev B's filter).
 *
 * <p>Contract (C3, C21, D-14, D-40): the key is computed <b>after model selection</b>, so it holds the resolved
 * model, never {@code auto}. It covers everything that changes the answer and nothing that does not:
 * <ul>
 *   <li>in: tenant id, resolved model, every message (role and content, in order), temperature, top_p,
 *       max tokens, stop sequences, the JSON schema (canonicalised: object keys sorted, insignificant whitespace
 *       removed, so semantically equal schemas give the same key, AC-t05-11);</li>
 *   <li>out: {@code stream}, {@code include_usage}, the request id, headers, the API key (two keys of one tenant
 *       share entries; tenants never do).</li>
 * </ul>
 * The key is {@code cache:v1:<sha256 hex of canonical JSON>}; the version prefix lets a format change start
 * from an empty cache instead of misreading old entries. Deterministic and thread-safe.
 */
public interface CacheKeyStrategy {

    String keyFor(String tenantId, ChatRequest resolvedRequest);
}
