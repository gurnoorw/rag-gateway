# rag-gateway: Low-Level Design (LLD)

> Where this document and the overrides in `CLAUDE.md` or `docs/process/DECISIONS.md` disagree, those win.

## Scope

This LLD zooms into the gateway service from the HLD: the classes, interfaces and the key algorithms. Java 21 throughout; one Lua script, because Redis atomic scripts must be Lua.

**Design principles:**

1. **Program to interfaces.** `LlmProvider`, `RateLimiter`, `ResponseCache`, `Retriever` are interfaces, so each part can be swapped and unit-tested with a fake.
2. **One class, one job.** The rate limiter doesn't know about caching; the router doesn't know about tenants.
3. **Immutable data.** Requests and responses are Java `record`s, safe to share between threads.
4. **Fail loudly at the edge, gracefully in the middle.** Bad input → 400 at once. Provider trouble → retry, fall back, then fail.

The code below shows structure and the key logic, not every import or Spring annotation. `docs/process/DECISIONS.md` records where the implementation differs.

## Class diagram

Left column: the request path, top to bottom. Middle: what the filters and router depend on. Right: RAG, which reuses the same `FilterChain` for its model call.

## Core models and the provider interface

Requests and responses are immutable records. The provider interface is the most important abstraction: every model vendor hides behind it.

```java
public enum Role { SYSTEM, USER, ASSISTANT }

public record Message(Role role, String content) {}

public record ChatRequest(String model, List<Message> messages, int maxTokens,
                          double temperature, boolean stream, String jsonSchema) {
    public ChatRequest {
        messages = List.copyOf(messages);                 // defensive, immutable copy
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
    }
}

public record Usage(int inputTokens, int outputTokens) {
    public int total() { return inputTokens + outputTokens; }
}

public record Tenant(String id, long tokensPerMinute, BigDecimal monthlyBudgetUsd,
                     Set<String> aclGroups) {}

/** Receives a streamed response, piece by piece. */
public interface StreamSink {
    void onToken(String text);
    void onComplete(Usage usage);
    void onError(Throwable error);
}

/**
 * One adapter per vendor. Contract: stream() blocks (it runs on a virtual thread),
 * calls sink.onToken for each piece, then sink.onComplete once, and returns.
 * On failure it throws ProviderException instead of calling onError,
 * so the router can decide whether to retry or fall back.
 */
public interface LlmProvider {
    String name();
    boolean supports(String model);
    void stream(ChatRequest request, StreamSink sink) throws ProviderException;
}

public final class ProviderException extends Exception {
    private final boolean retryable;                   // 429, 5xx, timeout = true; 400 = false
    public ProviderException(String msg, boolean retryable, Throwable cause) {
        super(msg, cause); this.retryable = retryable;
    }
    public boolean retryable() { return retryable; }
}
```

`ClaudeProvider` and `OllamaProvider` implement `LlmProvider`. Each translates `ChatRequest` into the vendor's own API format and reads the vendor's stream events back into `onToken` calls. That's the **Adapter** pattern: callers never see vendor-specific types.

Per-request state lives in one small mutable object that only one thread touches:

```java
public final class RequestContext {
    private final String requestId;
    private final String apiKey;
    private Tenant tenant;                 // set by AuthFilter
    private final ChatRequest request;
    private String providerUsed;
    private boolean cacheHit;
    // constructor, getters, setters
}
```

## The request pipeline: a chain of filters

Every request passes through the same ordered filters, then reaches the router. Each filter can stop the request (auth failure, rate limit, cache hit) or pass it on. This is the **Chain of Responsibility** pattern, the same idea as Servlet filters.

**Order:** `AuthFilter → CacheFilter → RateLimitFilter → ValidationFilter → FallbackRouter`. The cache sits before the rate limiter so cache hits are free and never touch the limiter.

```java
public interface GatewayFilter {
    void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException;
}

/** The last step: actually calls a provider. */
public interface Terminal {
    void call(RequestContext ctx, StreamSink sink);
}

/** Created fresh for each request, so the index is never shared between threads. */
public final class FilterChain {
    private final List<GatewayFilter> filters;
    private final Terminal terminal;
    private int index = 0;

    public FilterChain(List<GatewayFilter> filters, Terminal terminal) {
        this.filters = filters; this.terminal = terminal;
    }

    public void next(RequestContext ctx, StreamSink sink) throws GatewayException {
        if (index < filters.size()) filters.get(index++).handle(ctx, sink, this);
        else terminal.call(ctx, sink);
    }
}
```

Filters often need to act **when the response finishes** (refund tokens, store in cache). They do that by wrapping the sink. This is the **Decorator** pattern:

```java
public class ForwardingSink implements StreamSink {
    protected final StreamSink delegate;
    public ForwardingSink(StreamSink delegate) { this.delegate = delegate; }
    public void onToken(String text)    { delegate.onToken(text); }
    public void onComplete(Usage usage) { delegate.onComplete(usage); }
    public void onError(Throwable e)    { delegate.onError(e); }
}
```

The controller ties it together. With Java 21 virtual threads enabled (`spring.threads.virtual.enabled=true`), each request runs on its own cheap virtual thread, so blocking code is fine:

```java
@PostMapping("/v1/chat/completions")
public SseEmitter chat(@RequestHeader("Authorization") String auth,
                       @RequestBody ChatRequest request) {
    SseEmitter emitter = new SseEmitter(120_000L);
    StreamSink clientSink = new SseClientSink(emitter);          // writes SSE events
    executor.submit(() -> {                                       // virtual-thread executor
        RequestContext ctx = new RequestContext(UUID.randomUUID().toString(), auth, request);
        try {
            new FilterChain(filters, router).next(ctx, clientSink);
        } catch (GatewayException e) {
            clientSink.onError(e);                                // e.g. 401, 429
        }
    });
    return emitter;
}
```

(`AuthFilter` resolves the API key to a `Tenant` and sets it on the context; shown simplified here.)

## Token-bucket rate limiter

**The idea:** each tenant has a bucket holding up to `capacity` tokens. It refills at a steady rate (`tokensPerMinute / 60,000` per ms). A request takes tokens out; if there aren't enough, it's rejected with how long to wait. Bursts up to `capacity` are allowed, and the long-run rate is capped.

The single-machine version comes first, then the distributed one.

**Step 1: in-memory, one instance**

```java
public record Decision(boolean allowed, long retryAfterMs) {
    static Decision allow()           { return new Decision(true, 0); }
    static Decision deny(long waitMs) { return new Decision(false, waitMs); }
}

final class TokenBucket {
    private final long capacity;
    private final double refillPerMs;
    private double tokens;
    private long lastRefillMs;

    TokenBucket(long capacity, double refillPerMs, long nowMs) {
        this.capacity = capacity; this.refillPerMs = refillPerMs;
        this.tokens = capacity;   this.lastRefillMs = nowMs;
    }

    synchronized Decision tryAcquire(long n, long nowMs) {
        if (n > capacity) throw new IllegalArgumentException("request larger than bucket"); // -> 400
        refill(nowMs);
        if (tokens >= n) { tokens -= n; return Decision.allow(); }
        long waitMs = (long) Math.ceil((n - tokens) / refillPerMs);
        return Decision.deny(waitMs);
    }

    synchronized void refund(long n) {
        tokens = Math.min(capacity, tokens + n);
    }

    private void refill(long nowMs) {
        long elapsed = Math.max(0, nowMs - lastRefillMs);
        tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
        lastRefillMs = nowMs;
    }
}
```

**Step 2: distributed, shared by all instances (Redis + Lua)**

The read-refill-deduct must be **atomic**, or two instances can both read "enough tokens" and both deduct. A Lua script runs atomically inside Redis, so the same logic moves there:

```lua
-- KEYS[1] = "rl:{tenantId}"   ARGV = capacity, refill_per_ms, requested, now_ms
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local req      = tonumber(ARGV[3])
local now      = tonumber(ARGV[4])

local b      = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or capacity
local ts     = tonumber(b[2]) or now

tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)

local allowed, wait = 0, 0
if tokens >= req then
  tokens = tokens - req
  allowed = 1
else
  wait = math.ceil((req - tokens) / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) * 2)  -- idle buckets disappear
return {allowed, wait}
```

```java
public interface RateLimiter {
    Decision tryAcquire(String tenantId, long tokens, long tokensPerMinute);
    void refund(String tenantId, long tokens, long tokensPerMinute);
}

public final class RedisTokenBucketLimiter implements RateLimiter {
    private final StringRedisTemplate redis;
    private final RedisScript<List> acquireScript;      // the Lua above, loaded once
    private final Clock clock;

    public Decision tryAcquire(String tenantId, long tokens, long tokensPerMinute) {
        double perMs = tokensPerMinute / 60_000.0;
        List<Long> r = redis.execute(acquireScript, List.of("rl:" + tenantId),
                String.valueOf(tokensPerMinute), String.valueOf(perMs),
                String.valueOf(tokens), String.valueOf(clock.millis()));
        return r.get(0) == 1L ? Decision.allow() : Decision.deny(r.get(1));
    }
    // refund(): a similar small script: tokens = min(capacity, tokens + n)
}
```

**The filter: reserve first, settle at the end**

```java
public final class RateLimitFilter implements GatewayFilter {
    public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException {
        Tenant t = ctx.tenant();
        long reserved = tokenCounter.count(ctx.request()) + ctx.request().maxTokens();

        if (budget.isExceeded(t)) throw new BudgetExceededException();
        Decision d = limiter.tryAcquire(t.id(), reserved, t.tokensPerMinute());
        if (!d.allowed()) throw new RateLimitedException(d.retryAfterMs());   // -> 429 + Retry-After

        chain.next(ctx, new ForwardingSink(sink) {
            @Override public void onComplete(Usage used) {
                long unused = reserved - used.total();
                if (unused > 0) limiter.refund(t.id(), unused, t.tokensPerMinute());
                budget.add(t.id(), pricing.cost(ctx.providerUsed(), ctx.request().model(), used));
                super.onComplete(used);
            }
        });
    }
}
```

**Notes:** use the clock from Redis (`TIME`) instead of each server's clock if servers drift; if Redis is slow or down, call it with a short timeout and fail open with a local bucket; refund on errors too, so a failed call doesn't eat the tenant's quota.

## Circuit breaker and fallback router

**Circuit breaker states:** CLOSED (normal, calls go through) → OPEN (too many failures, calls are skipped instantly) → after a cool-down, HALF_OPEN (let one trial call through) → success: CLOSED, failure: OPEN again. It stops you hammering a provider that's already down, and fails fast instead of waiting on timeouts.

The service uses Resilience4j. This is what such a breaker does inside:

```java
public final class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int windowSize;                 // e.g. last 20 calls
    private final double failureRateThreshold;    // e.g. 0.5
    private final Duration openFor;               // e.g. 30 s
    private final Clock clock;

    private final Deque<Boolean> window = new ArrayDeque<>();   // true = success
    private State state = State.CLOSED;
    private Instant openedAt;
    private boolean trialInFlight;

    public synchronized boolean allowRequest() {
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openedAt.plus(openFor))) return false;
            state = State.HALF_OPEN;
            trialInFlight = false;
        }
        if (state == State.HALF_OPEN) {
            if (trialInFlight) return false;      // only one trial call at a time
            trialInFlight = true;
        }
        return true;
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) { state = State.CLOSED; window.clear(); return; }
        record(true);
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) { trip(); return; }
        record(false);
        if (window.size() == windowSize && failureRate() >= failureRateThreshold) trip();
    }

    private void record(boolean ok) {
        window.addLast(ok);
        if (window.size() > windowSize) window.removeFirst();
    }
    private double failureRate() {
        return window.stream().filter(ok -> !ok).count() / (double) window.size();
    }
    private void trip() { state = State.OPEN; openedAt = clock.instant(); window.clear(); }
}
```

**The router: retries, then fallback.** It walks the providers in priority order. For each, it retries a few times with backoff, but **never after the first token has reached the client** (the client would see duplicated text).

```java
public final class FallbackRouter implements Terminal {
    private final List<LlmProvider> providers;            // priority order: Claude, backup, local
    private final Map<String, CircuitBreaker> breakers;   // one per provider
    private final int maxAttempts = 2;

    public void call(RequestContext ctx, StreamSink sink) {
        Exception last = null;
        for (LlmProvider p : providers) {
            if (!p.supports(ctx.request().model())) continue;
            CircuitBreaker cb = breakers.get(p.name());

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (!cb.allowRequest()) break;                       // open: go to next provider
                FirstTokenSink tracking = new FirstTokenSink(sink);
                try {
                    ctx.setProviderUsed(p.name());
                    p.stream(ctx.request(), tracking);
                    cb.onSuccess();
                    return;                                          // done
                } catch (ProviderException e) {
                    cb.onFailure();
                    last = e;
                    if (tracking.started()) { sink.onError(e); return; } // mid-stream: can't retry
                    if (!e.retryable()) break;
                    sleepQuietly(backoff(attempt));
                }
            }
        }
        sink.onError(new AllProvidersFailedException(last));         // -> 503
    }

    /** Exponential backoff with full jitter: random wait in [0, base * 2^(attempt-1)], capped. */
    static Duration backoff(int attempt) {
        long capMs = Math.min(2_000, 200L << (attempt - 1));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capMs + 1));
    }
}

/** Remembers whether any token reached the client. */
final class FirstTokenSink extends ForwardingSink {
    private boolean started;
    FirstTokenSink(StreamSink d) { super(d); }
    @Override public void onToken(String text) { started = true; super.onToken(text); }
    boolean started() { return started; }
}
```

**Notes:** jitter stops all instances retrying at the same moment (thundering herd); sleeping is cheap on a virtual thread; timeouts live inside each provider adapter (connect ~1 s, first token ~5 s).

## Response cache

**Key rule:** two requests share a cache entry only if everything that affects the answer is equal: tenant, model, parameters, messages. Serialize those into canonical JSON (fixed field order) and hash it. Hashing keeps keys short and avoids delimiter bugs from string concatenation.

```java
public record CachedResponse(String content, Usage usage, String model) {}

public interface ResponseCache {
    Optional<CachedResponse> get(String key);
    void put(String key, CachedResponse value, Duration ttl);
}

final class CacheKeys {
    private record KeyParts(String tenantId, ChatRequest request) {}   // records serialize in field order

    static String of(String tenantId, ChatRequest r, ObjectMapper json) throws JsonProcessingException {
        byte[] canonical = json.writeValueAsBytes(new KeyParts(tenantId, r));
        return "cache:" + HexFormat.of().formatHex(sha256(canonical));
    }
}

public final class CacheFilter implements GatewayFilter {
    private final ResponseCache cache;
    private final Duration ttl = Duration.ofHours(6);

    public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException {
        ChatRequest r = ctx.request();
        if (r.temperature() > 0) { chain.next(ctx, sink); return; }   // non-deterministic: don't cache

        String key = CacheKeys.of(ctx.tenant().id(), r, json);
        Optional<CachedResponse> hit = cache.get(key);
        if (hit.isPresent()) {
            ctx.markCacheHit();
            sink.onToken(hit.get().content());
            sink.onComplete(new Usage(0, 0));      // no provider tokens spent
            return;
        }

        chain.next(ctx, new ForwardingSink(sink) {
            private final StringBuilder buffer = new StringBuilder();
            @Override public void onToken(String t) { buffer.append(t); super.onToken(t); }
            @Override public void onComplete(Usage u) {
                cache.put(key, new CachedResponse(buffer.toString(), u, r.model()), ttl);
                super.onComplete(u);
            }
            // onError: inherited, so failed responses are never cached
        });
    }
}
```

**Notes:** errors are never cached; the cache is per tenant on purpose (privacy over hit rate); for a stampede of identical requests, take a short Redis lock (`SET key NX PX 10000`) so only one goes to the provider; a semantic cache would implement the same `ResponseCache` interface with an embedding lookup inside.

## RAG: hybrid retriever, RRF and the ACL-filtered SQL

**Schema.** One table holds chunk text, its keyword index, its vector and its ACL groups, so one query can filter and search together.

```sql
CREATE TABLE chunks (
  chunk_id      text PRIMARY KEY,                 -- docId + ":" + ordinal
  doc_id        text NOT NULL REFERENCES documents(doc_id) ON DELETE CASCADE,
  collection_id text NOT NULL,
  ordinal       int  NOT NULL,
  text          text NOT NULL,
  tsv           tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
  embedding     vector(768) NOT NULL,
  acl_groups    text[] NOT NULL
);
CREATE INDEX chunks_vec ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunks_tsv ON chunks USING gin (tsv);
CREATE INDEX chunks_acl ON chunks USING gin (acl_groups);
```

**The two searches.** Both filter by ACL **inside** the query (`&&` means "arrays overlap"), so forbidden chunks never leave the database.

```sql
-- keyword search (Postgres full-text ranking; BM25-like, not exact BM25)
SELECT c.chunk_id
FROM chunks c, websearch_to_tsquery('english', :question) q
WHERE c.collection_id = :collection AND c.acl_groups && :groups AND c.tsv @@ q
ORDER BY ts_rank_cd(c.tsv, q) DESC
LIMIT 50;

-- vector search (<=> is cosine distance)
SELECT c.chunk_id
FROM chunks c
WHERE c.collection_id = :collection AND c.acl_groups && :groups
ORDER BY c.embedding <=> :questionVector
LIMIT 50;
```

**Merging with reciprocal rank fusion.** Each chunk's score is the sum of `1 / (60 + rank)` over the lists it appears in. It only uses ranks, so you never have to compare a text-rank score with a cosine distance.

```java
public interface Retriever {
    List<Chunk> retrieve(String collection, String question, Set<String> groups, int topK);
}

public final class HybridRetriever implements Retriever {
    private static final int RRF_K = 60;
    private static final int CANDIDATES = 50;
    private final ChunkRepository repo;
    private final Embedder embedder;
    private final ExecutorService executor;      // virtual threads

    public List<Chunk> retrieve(String collection, String question, Set<String> groups, int topK) {
        float[] qvec = embedder.embed(question);

        // run both searches in parallel
        var keyword = CompletableFuture.supplyAsync(
                () -> repo.keywordSearch(collection, question, groups, CANDIDATES), executor);
        var vector = CompletableFuture.supplyAsync(
                () -> repo.vectorSearch(collection, qvec, groups, CANDIDATES), executor);

        Map<String, Double> scores = new HashMap<>();
        addRrf(scores, keyword.join());
        addRrf(scores, vector.join());

        List<String> best = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
        return repo.findByIdsInOrder(best);
    }

    private static void addRrf(Map<String, Double> scores, List<String> rankedIds) {
        for (int i = 0; i < rankedIds.size(); i++) {
            scores.merge(rankedIds.get(i), 1.0 / (RRF_K + i + 1), Double::sum);   // rank is 1-based
        }
    }
}
```

**Answering.** `RagService` builds the prompt and sends it through the **same filter chain** as a normal chat request, so RAG gets limits, cache, fallback and metrics without new code:

```java
public RagAnswer answer(RequestContext ctx, String collection, String question) {
    List<Chunk> chunks = retriever.retrieve(collection, question, ctx.tenant().aclGroups(), 5);
    if (chunks.isEmpty()) return RagAnswer.noAnswer();      // don't let the model guess

    String context = chunks.stream()
            .map(c -> "[" + c.chunkId() + "] " + c.text())
            .collect(Collectors.joining("\n\n"));
    ChatRequest req = new ChatRequest("auto", List.of(
            new Message(Role.SYSTEM, "Answer only from the sources below. Cite source ids in brackets. "
                                   + "If the answer is not in the sources, say you don't know."),
            new Message(Role.USER, "Sources:\n" + context + "\n\nQuestion: " + question)),
            500, 0.0, false, null);

    CollectingSink sink = new CollectingSink();
    pipeline.run(ctx.withRequest(req), sink);
    return new RagAnswer(sink.text(), citationsFrom(chunks));
}
```

**Notes:** with a selective ACL filter, an HNSW index can return fewer than 50 rows; pgvector's iterative index scan or partitioning by collection fixes that; the ACL test in the eval set must show zero leaked chunks.

## Indexing worker and the outbox

**Write side: save the document and the job together.** One transaction writes the document row and an outbox row. Either both exist or neither does, so a saved document can never be left un-indexed.

```java
@Transactional
public UpsertResult upsert(DocumentUpsert d) {
    String hash = sha256Hex(d.content() + "|" + String.join(",", new TreeSet<>(d.aclGroups())));
    Optional<String> current = documents.findHash(d.docId());
    if (current.isPresent() && current.get().equals(hash)) return UpsertResult.UNCHANGED;  // skip

    documents.upsert(d, hash);
    outbox.insert(UUID.randomUUID(), d.docId(), hash);
    return UpsertResult.QUEUED;                        // controller returns 202
}
```

The hash covers the ACL groups too, so a permission change re-indexes the chunks (their copied `acl_groups` must change).

**Claiming work safely across several workers.** Each worker takes a batch with a time-limited lease. `SKIP LOCKED` means two workers never grab the same row; the lease means a crashed worker's rows are picked up again later.

```sql
UPDATE outbox SET status = 'IN_PROGRESS', lease_until = now() + interval '5 minutes',
                  attempts = attempts + 1
WHERE id IN (
  SELECT id FROM outbox
  WHERE status = 'PENDING' OR (status = 'IN_PROGRESS' AND lease_until < now())
  ORDER BY created_at
  LIMIT 20
  FOR UPDATE SKIP LOCKED
)
RETURNING id, doc_id, content_hash, attempts;
```

**Processing.** Slow work (embedding calls) happens **outside** any database transaction; only the final swap of chunks is transactional.

```java
public final class IndexingWorker {
    private static final int MAX_ATTEMPTS = 5;

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        for (OutboxEvent e : outbox.claimBatch(20)) {
            try {
                index(e);
                outbox.markDone(e.id());
            } catch (Exception ex) {
                if (e.attempts() >= MAX_ATTEMPTS) outbox.markDead(e.id(), ex.getMessage()); // alert
                else outbox.release(e.id());          // back to PENDING, retried later
            }
        }
    }

    private void index(OutboxEvent e) {
        Document doc = documents.get(e.docId());
        if (!doc.contentHash().equals(e.contentHash())) return;  // a newer version is queued; skip stale job

        List<String> pieces = chunker.split(doc.content());       // ~400 tokens, small overlap
        List<float[]> vectors = embedder.embedBatch(pieces);      // one call per batch, not per chunk
        chunks.replaceForDocument(doc, pieces, vectors);          // @Transactional: delete old + insert new
    }
}
```

**Why it's safe to retry:** `replaceForDocument` deletes all of the document's chunks and inserts the new set in one transaction. Running it twice gives the same result (idempotent). Readers see either the old chunks or the new ones, never a mix.

## Patterns, thread safety and follow-up questions

**Patterns used (and the one-line reason):**

| Pattern | Where | Why |
| --- | --- | --- |
| Adapter | `ClaudeProvider`, `OllamaProvider` | Hide each vendor's API behind one interface |
| Chain of Responsibility | `FilterChain` + filters | Add or reorder cross-cutting steps without touching the others |
| Decorator | `ForwardingSink` subclasses | Act when a stream finishes without changing the provider code |
| Strategy | `Retriever`, `RateLimiter`, `ResponseCache` | Swap implementations (vector-only vs hybrid, Redis vs in-memory) |
| Circuit Breaker | `CircuitBreaker` per provider | Fail fast, give a sick provider time to recover |
| Transactional Outbox | `upsert` + `IndexingWorker` | Never lose an indexing job, never index a doc that wasn't saved |

**Thread safety:**

- Records (`ChatRequest`, `Usage`, `Tenant`) are immutable, so they're safe to share.
- `RequestContext`, `FilterChain` and the sinks are created per request and used by one virtual thread, so they need no locks.
- `TokenBucket` and `CircuitBreaker` are shared, so their methods are `synchronized`. Contention is low (one breaker per provider).
- In Java 21, a virtual thread that blocks **inside** a `synchronized` block pins its carrier thread. These blocks never do I/O, so that's fine. Where a lock must be held across I/O, use `ReentrantLock` instead. (Java 24 removes this pinning.)
- The distributed limiter's atomicity comes from the Lua script running as one unit in Redis, not from Java locks.

**Design FAQ:**

- *How do you add a new provider?* Write one class implementing `LlmProvider` and add it to the router's list in config. No other class changes (open/closed principle).
- *How do you test the router?* A fake `LlmProvider` that fails N times then succeeds, plus an injected `Clock` so breaker timing is testable without sleeping.
- *How do you test the Redis limiter?* Testcontainers with a real Redis; fire 100 parallel requests and assert exactly the allowed number pass.
- *Why pass a `Clock` everywhere?* Time-based logic (buckets, breakers, leases) becomes deterministic in tests.
- *What if the tokenizer estimate is wrong?* The reservation is corrected at the end with the provider's real usage numbers; only the short-term limit is approximate, never the bill.
- *How would you add per-tenant model routing?* A `RoutingPolicy` strategy chosen by tenant config, used by the router to order its provider list.
