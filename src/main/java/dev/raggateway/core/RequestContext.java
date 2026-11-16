package dev.raggateway.core;

import dev.raggateway.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable state of one request. Created by the HTTP edge (or by the RAG service for its model call),
 * then read and updated by filters, the terminal and the completion listeners. One request runs on
 * one virtual thread from start to end, so this class is not thread-safe and needs no locks
 * (LLD "Thread safety").
 *
 * <p>Differs from the LLD: the presented API key is not stored (authentication happens at the edge,
 * DECISIONS.md D-14), and provider attempts are kept as a list so billing and logging can see every
 * attempt, not only the last provider name.
 */
public final class RequestContext {

    private final String requestId;
    private final long startNanos;
    private final ApiKeyPrincipal caller;
    private final String requestedModel;
    private ChatRequest request;
    private String routeRule;
    private CacheStatus cacheStatus = CacheStatus.NOT_ELIGIBLE;
    private boolean cacheBypassRequested;
    private final List<Attempt> attempts = new ArrayList<>();
    private long firstTokenNanos = -1;
    private String currentProvider;
    private String currentModel;
    private boolean currentFallback;
    private long finishNanos = -1;
    private RequestOutcome outcome;
    private ErrorCode errorCode;
    private int httpStatus;

    /**
     * @param requestId  id generated at the edge, returned in {@code X-Request-Id} (G8)
     * @param startNanos {@link System#nanoTime()} when the edge received the request
     * @param caller     authenticated caller
     * @param request    request as sent by the caller (model may be {@link ChatRequest#AUTO})
     */
    public RequestContext(String requestId, long startNanos, ApiKeyPrincipal caller, ChatRequest request) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.startNanos = startNanos;
        this.caller = Objects.requireNonNull(caller, "caller");
        this.request = Objects.requireNonNull(request, "request");
        this.requestedModel = request.model();
    }

    public String requestId() { return requestId; }

    public long startNanos() { return startNanos; }

    public ApiKeyPrincipal caller() { return caller; }

    public Tenant tenant() { return caller.tenant(); }

    /** Model name exactly as the caller sent it (may be {@code auto}). */
    public String requestedModel() { return requestedModel; }

    /** Current request; after model selection its model is the resolved model. */
    public ChatRequest request() { return request; }

    /** Replace the request; only model selection (t06) calls this. */
    public void setRequest(ChatRequest request) { this.request = Objects.requireNonNull(request); }

    /** Name of the routing rule that chose the model, or null when the caller named a model. */
    public String routeRule() { return routeRule; }

    public void setRouteRule(String routeRule) { this.routeRule = routeRule; }

    public CacheStatus cacheStatus() { return cacheStatus; }

    /** True when the caller sent {@code X-Gateway-Cache-Bypass: true}; set by the edge (t01), read by the cache (t05). */
    public boolean cacheBypassRequested() { return cacheBypassRequested; }

    public void setCacheBypassRequested(boolean cacheBypassRequested) { this.cacheBypassRequested = cacheBypassRequested; }

    public void setCacheStatus(CacheStatus cacheStatus) { this.cacheStatus = Objects.requireNonNull(cacheStatus); }

    /**
     * Announce the provider attempt about to start, before the adapter is called (DECISIONS.md D-28). The
     * client sink reads it when it commits headers at the first byte, so {@code X-Gateway-Provider},
     * {@code X-Gateway-Model} and the body's {@code model} name the provider that is actually streaming,
     * including a fallback (t03). Every terminal calls this once per attempt, then {@link #recordAttempt}
     * when the attempt ends.
     *
     * @param provider provider name, e.g. {@code claude}
     * @param model    public model name sent to that provider
     * @param fallback true when this is not the provider model selection chose
     */
    public void startAttempt(String provider, String model, boolean fallback) {
        this.currentProvider = Objects.requireNonNull(provider, "provider");
        this.currentModel = Objects.requireNonNull(model, "model");
        this.currentFallback = fallback;
    }

    /** Provider of the attempt in progress (or the last one started), or null before any attempt. */
    public String currentProvider() { return currentProvider; }

    /** Public model name of the attempt in progress (or the last one started), or null. */
    public String currentModel() { return currentModel; }

    /** Whether the attempt in progress is a fallback. */
    public boolean currentAttemptIsFallback() { return currentFallback; }

    /** Record one provider attempt. Called by the terminal once per attempt, in order. */
    public void recordAttempt(Attempt attempt) { attempts.add(Objects.requireNonNull(attempt)); }

    /** All provider attempts in order; empty for a cache hit or a request rejected before the provider. */
    public List<Attempt> attempts() { return List.copyOf(attempts); }

    /** The attempt whose output reached (or was meant for) the client: the last attempt, if any. */
    public Optional<Attempt> servingAttempt() {
        return attempts.isEmpty() ? Optional.empty() : Optional.of(attempts.get(attempts.size() - 1));
    }

    /** Sum of usage over all attempts: what the tenant is charged for. */
    public Usage billedUsage() {
        Usage total = Usage.ZERO;
        for (Attempt a : attempts) total = total.plus(a.usage());
        return total;
    }

    /** True when the serving attempt came from a fallback provider (C21: never cached). */
    public boolean fallbackUsed() {
        return servingAttempt().map(Attempt::fallback).orElse(false);
    }

    /** Mark the moment the first token was written to the client; only the first call counts. */
    public void markFirstTokenSent(long nowNanos) {
        if (firstTokenNanos < 0) firstTokenNanos = nowNanos;
    }

    /** {@link System#nanoTime()} of the first token sent to the client, or -1. */
    public long firstTokenNanos() { return firstTokenNanos; }

    public boolean firstTokenSent() { return firstTokenNanos >= 0; }

    /** Set by the pipeline when the request ends; the end time is now. */
    public void finish(RequestOutcome outcome, ErrorCode errorCode, int httpStatus) {
        finish(outcome, errorCode, httpStatus, System.nanoTime());
    }

    /**
     * Set by the pipeline when the request ends, after the response was written and closed. The end time is
     * frozen here so every completion listener (log row, metrics) reports the same latency (D-31).
     */
    public void finish(RequestOutcome outcome, ErrorCode errorCode, int httpStatus, long finishNanos) {
        this.outcome = Objects.requireNonNull(outcome);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.finishNanos = finishNanos;
    }

    /** {@link System#nanoTime()} when the request finished, or -1 before {@link #finish}. */
    public long finishNanos() { return finishNanos; }

    /** Null until the pipeline finished the request. */
    public RequestOutcome outcome() { return outcome; }

    /** Error code of a failed request, else null. */
    public ErrorCode errorCode() { return errorCode; }

    /** HTTP status the client saw (200 for a stream that failed after the first token). */
    public int httpStatus() { return httpStatus; }
}
