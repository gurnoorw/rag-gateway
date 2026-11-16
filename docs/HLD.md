# rag-gateway: System Design (HLD)

> Where this document and the overrides in `CLAUDE.md` or `docs/process/DECISIONS.md` disagree, those win.

## Requirements

**The problem in one line:** many internal apps call LLMs directly, so nobody controls cost, limits or failures. Build one service in front of the LLM providers that does, and that also answers questions over company documents (RAG).

**Functional requirements**

1. Apps can send a chat request and get a model response, streamed token by token.
2. Each tenant is limited by tokens per minute and by a monthly budget.
3. If a provider fails or is slow, the request still succeeds on another provider.
4. Repeated identical requests are served from a cache.
5. Users can ask a question over a document collection and get an answer with citations, built only from documents they're allowed to see.
6. Admins can add or update documents, and only changed documents are re-indexed.

**Non-functional requirements**

| Requirement | Target | Why it matters |
| --- | --- | --- |
| Low added latency | Gateway adds < 50 ms p99 before the first token | The model is already slow (seconds); the gateway must not add to it |
| High availability | 99.9%, and survives one provider outage | It's on the path of every LLM call in the company |
| Correct limits | No tenant goes meaningfully over budget, even with many gateway instances | Budgets are money |
| Security | Zero chunks leaked across ACL boundaries | One leaked HR document is a serious incident |
| Observability | Latency, tokens, cost, errors per tenant and provider | You can't control what you can't see |
| Scale | Horizontally scalable, stateless gateway | Add instances as traffic grows |

**Out of scope:** model training or fine-tuning, a chat UI, user sign-up and SSO (API keys only), multi-region, semantic cache and async bulk jobs (mention as extensions).

## Capacity estimates

Assumptions for a mid-size company. The math is done only where it changes a decision. These are design assumptions, not measurements; measured numbers live in the README.

| Quantity | Estimate | Working |
| --- | --- | --- |
| Average QPS | 300 | ~26M requests/day across ~50 tenant apps |
| Peak QPS | 1,000 | ~3x average |
| Tokens per request | ~2,000 | ~1,500 in + ~500 out |
| **Concurrent open requests at peak** | **~10,000** | Little's law: 1,000 req/s × ~10 s per streamed response |
| Redis ops at peak | ~4,000/s | ~4 ops per request (limit, budget, cache get/set) |
| Request logs | ~26 GB/day | 26M × ~1 KB; keep 90 days ≈ 2.3 TB, partition by day |
| RAG queries | ~100 QPS | ~10% of traffic |
| RAG corpus | ~100 GB | 1M docs × 20 chunks = 20M chunks; 768-dim float vector ≈ 3 KB + ~2 KB text each |
| Ingestion writes | ~10K changed docs/day | Mostly reads; writes are small and batchable |

**What the numbers tell us:**

- The bottleneck is **concurrent open connections**, not QPS. 10,000 requests waiting on a slow model means you need non-blocking I/O or Java 21 virtual threads, not one OS thread per request.
- Redis load is tiny. One Redis primary with a replica handles it easily.
- The vector index fits one Postgres instance with pgvector at this size. A dedicated vector DB only becomes worth it at hundreds of millions of chunks.
- The system is read-heavy (queries) with light, batchable writes (ingestion).

## Core entities

| Entity | Key fields | Stored in |
| --- | --- | --- |
| Tenant | tenantId, apiKeyHash, tokensPerMinute, monthlyBudgetUsd, allowedModels | Postgres (cached in memory) |
| Request log | requestId, tenantId, model, provider, inputTokens, outputTokens, costUsd, latencyMs, status, cacheHit, createdAt | Postgres, partitioned by day |
| Provider | name, baseUrl, models, timeoutMs, priority | Config |
| Document | docId, collectionId, title, contentHash, aclGroups, updatedAt | Postgres |
| Chunk | chunkId, docId, text, embedding (vector), tsvector, aclGroups | Postgres + pgvector |
| Usage counters | tokens used this minute, spend this month | Redis |
| Cached response | key = hash(model, params, messages, tenant), value = response | Redis, with TTL |

## API design

One endpoint per functional requirement. All calls send `Authorization: Bearer <api-key>`; the tenant comes from the key, never from the body.

```
POST /v1/chat/completions          (OpenAI-compatible)
{ "model": "auto" | "claude-sonnet", "messages": [{"role":"user","content":"..."}],
  "stream": true, "max_tokens": 500, "response_format": {json schema}? }
-> 200 text/event-stream: data: {"choices":[{"delta":{"content":"Hel"}}]} ... data: [DONE]
-> 429 { "error": "rate_limited" | "budget_exceeded" }  + Retry-After

POST /v1/rag/query
{ "collection": "eng-handbook", "question": "How do I request prod access?", "topK": 5 }
-> 200 { "answer": "...", "citations": [{ "docId", "title", "chunkId" }] }

PUT /v1/rag/collections/{collection}/documents/{docId}
{ "title", "content", "aclGroups": ["eng", "sre"] }
-> 202 Accepted   (indexing is async)

GET /v1/usage?from=&to=            -> tokens and cost for the caller's tenant
```

**Why OpenAI-compatible:** it's the format most SDKs and tools already speak, so an app switches to the gateway by changing one base URL. Inside, an adapter per provider translates to its native API.

**Why PUT for documents:** it's idempotent. Sending the same document twice is safe, which matters for retries.

## High-level design

The design is built up one functional requirement at a time.

**FR1: send a chat request, get a streamed answer.** Client → load balancer → gateway service → provider. The gateway looks up the tenant from the API key, picks a provider adapter, opens a stream to it and forwards each chunk to the client over SSE. When the stream ends, it writes a request log row.

**FR2: token limits and budgets.** Before calling the provider, the gateway reserves tokens in Redis for the tenant. Over the limit → 429. When the stream ends, it refunds unused tokens and adds the cost to the monthly budget counter. Redis is used because every gateway instance must see the same counters.

**FR3: survive a provider outage.** Each provider adapter is wrapped in a timeout, a retry and a circuit breaker. If the primary's breaker is open, the router goes straight to the next provider in the chain.

**FR4: cache repeated requests.** Before calling the provider, check Redis for `hash(tenant, model, params, messages)`. Hit → return it immediately. Miss → call, then store with a TTL.

**FR5: RAG with access control.** The gateway embeds the question, runs one Postgres query that does keyword search + vector search filtered by the user's ACL groups, merges the results, builds a prompt with the top chunks, and sends it through the same path as FR1–FR4. So RAG gets limits, fallback and caching for free.

**FR6: incremental indexing.** `PUT document` writes the document and an outbox row in one transaction and returns 202. An indexer worker picks up the outbox row, chunks the text, calls the embedding model in batches, and replaces that document's chunks. Unchanged content (same hash) is skipped.

## Deep dives

Each one lists a naive approach, a better one, the chosen one, and the trade-off.

### 1. How do you enforce token limits across many gateway instances?

The catch: you don't know the output token count until the response finishes.

- **Naive:** an in-memory counter per instance. With 5 instances, a tenant gets 5x their limit.
- **Better:** a fixed-window counter in Redis (`INCRBY tenant:{id}:min:{minute}`). Shared and simple, but allows a 2x burst at window edges.
- **Chosen:** a token bucket in Redis, updated by one Lua script so check-and-deduct is atomic. **Reserve** `inputTokens + max_tokens` before the call, then **refund** the unused part when the stream ends. Budget is a separate monthly counter checked the same way.
- **Trade-off:** if Redis is down, do you fail open (allow traffic, risk overspend) or fail closed (block everyone)? Fail open with a short local fallback limit is the usual choice for internal traffic.

### 2. What happens when a provider is slow or down?

- **Naive:** wait for the default HTTP timeout (30–60 s), then return 500.
- **Better:** tight timeouts plus retries with exponential backoff and jitter.
- **Chosen:** layered protection per provider:
  1. Two timeouts: connect (~1 s) and time-to-first-token (~5 s), since a stream can legitimately run long.
  2. Retry only **before the first token** is sent to the client. After that, you can't retry without the client seeing duplicate text.
  3. A circuit breaker per provider: open after, say, 50% failures over the last 20 calls, then half-open after 30 s to test.
  4. A fallback chain: Claude, then another provider, then a local model. Responses carry the model actually used.
- **Trade-off:** a weaker fallback model may give a worse answer. Let tenants opt out of fallback if quality matters more than availability.

### 3. How do you cache LLM responses safely?

- **Key:** `hash(tenant, model, params, messages)`. Include the tenant so one tenant never sees another's cached answer.
- **Only cache deterministic requests** (temperature 0) and skip anything flagged as personal data.
- **TTL:** hours, not days; source data and models change.
- **Cache stampede:** 100 identical requests at once should make one provider call. Use a short Redis lock (single-flight); others wait briefly, then read the cache.
- **Extension: semantic cache.** Match similar prompts by embedding distance. Higher hit rate, but a false hit returns a wrong answer. Needs a strict similarity threshold and per-tenant opt-in.

### 4. How do you hold 10,000 open streams?

- **Naive:** classic Spring MVC with a 200-thread pool. Thread 201 waits; latency explodes.
- **Better:** reactive (Spring WebFlux) end to end. Scales well, but harder to write, debug and explain.
- **Chosen:** Java 21 **virtual threads** with plain blocking code. Each request gets a cheap virtual thread, so 10,000 waiting calls cost little memory. Simple code, same scaling for I/O-bound work.
- **Also:** if the client disconnects, cancel the upstream call so you stop paying for tokens nobody reads. Count output tokens as they stream and settle cost when the stream ends.

### 5. How do you get good retrieval?

- **Naive:** vector search only. It misses exact terms like error codes, product names and IDs.
- **Better:** keyword search (Postgres full-text) plus vector search (pgvector), run in parallel.
- **Chosen:** merge the two ranked lists with **reciprocal rank fusion**: each chunk scores `sum(1 / (60 + rank))` across both lists. No score normalisation needed. Optionally re-rank the top 20 with a cross-encoder.
- **Chunking:** ~300–500 tokens with a small overlap, split on headings and paragraphs, never mid-sentence. Store the doc title with each chunk.
- **Prove it:** a fixed eval set of ~30 questions with known source chunks. Report **recall@5** for each change (chunk size, hybrid vs vector-only). Numbers beat opinions.

### 6. How do you make sure users only see documents they're allowed to?

- **Naive:** retrieve the top 5, then drop the ones the user can't see. The user may get 0 results, and the LLM may already have seen the hidden text if you filter late.
- **Better:** filter **inside** the search query: `WHERE acl_groups && :userGroups`, so forbidden chunks never leave the database.
- **Chosen:** pre-filter as above, plus watch the vector index. An approximate index (HNSW) with a selective filter can return too few rows; use pgvector's iterative index scan, or partition by collection so each index is smaller. Add an eval test that asks questions as an unauthorised user and asserts zero leaked chunks.
- **Trade-off:** ACLs are copied onto chunks at index time. When a document's permissions change, you must update its chunks quickly; treat a permission change like a content change.

### 7. How do you re-index only what changed?

- On `PUT document`: compute `sha256(content)`. Same hash as stored → return 202 and do nothing.
- Changed → write the doc row and an outbox row in **one transaction**, then return 202.
- A worker reads the outbox, chunks, embeds (in batches, to save cost), and in one transaction deletes the doc's old chunks and inserts the new ones. Retrying is safe because the operation is a full replace (idempotent).
- **Why an outbox:** you never get a saved document without an indexing job, or a job without a document.

### 8. How does the whole thing scale?

- The gateway is **stateless**: add instances behind a load balancer. All shared state is in Redis and Postgres.
- Request logs are written **async in batches** (buffer in memory, flush every second or 500 rows). Losing a few log rows on a crash is acceptable; budgets come from Redis, not the logs.
- Postgres: a read replica serves RAG search; the primary takes writes. Partition logs by day and drop old partitions instead of deleting rows.
- Redis: one primary + replica is enough at ~4,000 ops/s. Shard by tenantId if it grows.

## Design FAQ

- *Why not call the provider directly from each app?* Then limits, fallback, cost and keys are reimplemented in every app, and nobody sees total spend.
- *Isn't the gateway a single point of failure?* It's stateless and runs as several instances behind a load balancer; Redis and Postgres have replicas.
- *Why pgvector and not Pinecone or Elasticsearch?* At 20M chunks one Postgres holds it, and ACL filter, keyword search and vectors run in one query. I'd move at hundreds of millions of chunks.
- *How do you count tokens before the call?* A tokenizer estimate for input plus `max_tokens` as the reservation, refunded at the end.
- *How do you know RAG answers are good?* A fixed eval set; recall@5 for retrieval and a scored answer set, run on every change.
- *What would you build next?* Semantic cache, an async batch path on a queue, per-tenant model routing policies.
