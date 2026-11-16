# rag-gateway: concepts

> Where this document and the overrides in `CLAUDE.md` or `docs/process/DECISIONS.md` disagree, those win.

## The picture

rag-gateway is one Java service that sits between apps and LLM providers. Apps call it instead of calling Claude or OpenAI directly. It makes those calls cheaper (caching, routing), safer (rate limits, budgets, output checks) and more reliable (fallback, retries). On top of that gateway it adds a RAG endpoint: ask a question over your own documents and get an answer grounded in them, with each user seeing only the documents they're allowed to.

Think of it as the plumbing a company puts in front of its LLM usage. It's infrastructure, not a chatbot. The only UI is a minimal demo page.

The deliverable is not only the code. The README shows an architecture diagram, measured benchmark numbers and the design trade-offs.

## Design principles

1. **Every component has a reason.** Each one records why it exists and what the alternative was, in `docs/process/DECISIONS.md`.
2. **The numbers are the product.** Benchmarks are measured, never estimated, and labelled with the hardware they ran on.

## Key concepts, in plain words

| Concept | What it is | Analogy from systems you know |
| --- | --- | --- |
| LLM gateway | One service all LLM calls go through, so policy lives in one place | An API gateway, but the backend is a model provider |
| OpenAI-compatible API | Accept the same request shape as OpenAI's /v1/chat/completions, so any client SDK works unchanged | Speaking a standard protocol instead of a custom one |
| SSE streaming | Tokens are sent to the client as they're generated, over one HTTP response | A chunked response the client reads incrementally |
| Token-based rate limiting | Limit each tenant by tokens per minute, not requests, because one request can cost 50 or 50,000 tokens | A token-bucket limiter in Redis, weighted by cost |
| Budget and cost attribution | Count tokens per request, price them per model, store per tenant | Metering and billing records in Postgres |
| Provider fallback | If the primary provider times out or errors, retry with backoff, then switch to another provider or a local model | Failover with a circuit breaker per downstream |
| Response cache (exact match) | Same normalized prompt + model + params = return the stored answer | A read-through cache keyed by a hash |
| Semantic cache | Similar (not identical) prompts hit the cache, using embedding similarity above a threshold | A cache whose key is "close enough", with a false-hit risk |
| Model routing | Pick the model per request by rules (cheap model for short or simple asks, strong model otherwise) | A routing table driven by cost and latency |
| Output validation | Check model output against a JSON schema; retry or reject if it fails | Input validation, applied to a non-deterministic upstream |
| Embeddings | A vector of numbers that captures a text's meaning; similar texts sit close together | A hash where nearby keys mean nearby meanings |
| RAG | Retrieve relevant chunks of your documents, put them in the prompt, and have the model answer from them | A lookup step before the computation |
| Chunking | Split documents into small passages (a few hundred tokens) so retrieval returns focused text | Partitioning data for targeted reads |
| pgvector | A Postgres extension that stores vectors and finds nearest neighbours | An index type in a database you already run |
| Hybrid search + RRF | Run keyword search (BM25) and vector search, then merge the two ranked lists with reciprocal rank fusion | Combining two ranked signals into one score |
| Incremental indexing | Re-embed only documents whose content hash changed | Change data capture instead of full reloads |
| Chunk-level ACL | Each chunk carries who may see it; retrieval filters by the caller's groups before ranking | Row-level security |
| RAG evals | A fixed set of ~30 questions with known answers; measure recall@k for retrieval and score the answers | A regression test suite for quality |

## Architecture

The RAG endpoint doesn't get its own copy of the rate limiting, fallback and metrics logic. It reuses the gateway pipeline, so it gets all of that for free.

## The two request paths, step by step

**Path 1: chat completion (POST /v1/chat/completions)**

1. Authenticate the API key and resolve the tenant.
2. Rate limit: check the tenant's token bucket in Redis. Over the limit → 429 with Retry-After. Over budget → 429 with a clear payload.
3. Route: pick the model and provider from the routing rules.
4. Cache: look up the exact-match key in Redis. Hit → return the stored response (skips the provider).
5. Call the provider with a timeout. On error or timeout → retry with backoff and jitter; the circuit breaker opens after repeated failures → fall back to the secondary provider or the local model.
6. Stream the response back to the client over SSE.
7. Validate the output against a JSON schema when the request asked for structured output.
8. Record: write the request log and token cost to Postgres, emit Prometheus metrics, populate the cache.

**Path 2: RAG query (POST /v1/rag/query)**

1. Authenticate and resolve the caller's tenant and groups.
2. Embed the question.
3. Retrieve: run BM25 and vector search in Postgres, filtered by the caller's ACL groups, and merge with RRF to get the top-k chunks.
4. Build the prompt: question + retrieved chunks + an instruction to answer only from them and cite sources.
5. Send it through Path 1 (so rate limits, fallback, caching and metrics all apply for free).
6. Return the answer with the chunk citations.

**Ingestion (asynchronous, `PUT /v1/rag/collections/{collection}/documents/{docId}` → 202)**: document → hash content → skip if unchanged → outbox job → chunk → embed → store chunks, vectors and ACL groups in Postgres.

## Tech stack and why

The stack is fixed. Don't substitute.

| Piece | Used for | Why this and not something else |
| --- | --- | --- |
| Java 21 + Spring Boot 3 | The gateway service | Mature ecosystem; virtual threads suit I/O-bound provider calls |
| Redis | Rate limiting, budgets, response cache | Atomic counters and TTLs; shared state across gateway instances |
| PostgreSQL + pgvector | Request logs, cost records, chunks, vectors, ACLs, full-text (BM25-style) search | One store for metadata and vectors keeps ACL filtering and hybrid search in a single query |
| Resilience4j | Circuit breaker, retries, timeouts | Standard in the Spring ecosystem; easy to explain |
| Ollama (local model) | Fallback provider and cheap dev testing | Keeps API cost near zero; proves fallback works when you kill the primary |
| Prometheus + Grafana | Metrics and a dashboard checked into the repo | Industry default; the dashboard screenshot goes in the README |
| Docker Compose | Whole stack locally | A stranger can clone, run one command and reproduce your numbers |
| JUnit 5 + Testcontainers | Tests against real Redis and Postgres | Integration tests that match production |
| k6 | Load tests | Produces the p50/p95/p99 and throughput numbers |

## Build plan

The 15 tasks (t00–t14) are in `docs/ROADMAP.md`. Tasks t01–t08 make the gateway demo-able with measured numbers; t09–t14 add RAG, ACLs and evals.

## Key trade-offs

Each has a choice, an alternative and a reason. The decisions taken are recorded in `docs/process/DECISIONS.md`.

| Decision | Options | Questions to answer before you choose |
| --- | --- | --- |
| Rate-limit algorithm | Token bucket vs sliding window | Do you want to allow bursts? How do you deduct tokens you only know after the response finishes? |
| Where limits live | Redis (shared) vs in-memory per instance | What happens with 3 gateway instances? What if Redis is down: fail open or fail closed? |
| Cache type | Exact match vs semantic | What's an acceptable false-hit rate? Which requests must never be cached (temperature > 0, personal data)? |
| Cache key | What goes in the hash | Model, params, system prompt, tenant? Should two tenants ever share a cache entry? |
| Fallback trigger | Timeout vs error codes vs circuit breaker state | How long do you wait before giving up? Is a weaker fallback answer better than an error? |
| Streaming + retries | Retry before the first token only, or never mid-stream | What does the client see if the provider dies halfway through a stream? |
| Vector store | pgvector vs Redis vector search vs a dedicated vector DB | Why keep vectors next to ACLs and text? At what corpus size would you switch? |
| Chunk size | Small (precise) vs large (more context) | Measure it with recall@k from your eval set rather than guessing |
| ACL filtering | Pre-filter before ranking vs post-filter after | Why can post-filtering leak information or return too few results? |
| Cost logging | Synchronous write vs async | Can a lost log row cause a budget overrun? |

## Numbers to measure

These go in the README's benchmark table. All measured, none estimated.

| Metric | How to measure | Target to aim for |
| --- | --- | --- |
| Gateway overhead (p50/p95/p99) | k6 against a mock provider, gateway on vs off | Set it yourself after the first run |
| Cache-hit latency p99 | k6 with a repeated prompt set | Under 200 ms (from the original brief) |
| Cache hit rate | Prometheus counter over a realistic prompt mix | Report what you get |
| Cost per 1k requests | Postgres cost records, with and without cache and routing | Report the % saved |
| Fallback success | Kill the primary provider mid-load-test | Requests still succeed |
| Rate-limit correctness | Tenant over budget gets 429 with Retry-After | 100% of over-limit calls |
| Retrieval recall@k | 30-question eval set | Report per chunking setting |
| Answer quality | Score answers on the eval set | Report baseline vs hybrid search |
| ACL leakage | Eval questions asked as a user without access | Zero leaked chunks |

## Out of scope and open decisions

**Out of scope for v1:** web UI beyond a demo page, auth beyond API keys, multi-region, fine-tuning, Kubernetes, splitting into microservices.

**Stretch, only after v1.0 is tagged:**

- Semantic cache in front of the exact-match cache
- Async bulk path on Kafka: batched inference, configurable batch size and linger, dead-letter queue
- Deploying publicly, with a hard rate limit so strangers can't burn your API credits

Open questions (demo corpus, provider choice, semantic cache timing) are settled in `docs/process/DECISIONS.md`.
