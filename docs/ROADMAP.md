# rag-gateway roadmap

## What we're building

One Spring Boot service between apps and LLM providers:

- **Gateway path** (`POST /v1/chat/completions`, OpenAI-compatible): API key → tenant, token rate limit and budget (Redis, 429 + `Retry-After`), model router, exact-match cache (Redis), provider call (timeouts, retries with backoff and jitter, circuit breaker, fallback), output validation (JSON schema) and SSE streaming, cost log (Postgres) and Prometheus metrics.
- **RAG path** (`POST /v1/rag/query`): embed the question, hybrid search (Postgres full-text + pgvector, merged with reciprocal rank fusion) filtered by the caller's ACL groups, build the prompt from the chunks, then send it through the gateway path. Returns the answer with citations.
- **Ingestion** (`PUT /v1/rag/collections/{collection}/documents/{docId}`): content hash (skip if unchanged), chunk, embed, store chunks, vectors and ACL groups in Postgres. Indexing is asynchronous through a transactional outbox.

Two rules:

1. Every component has a stated reason and a rejected alternative, recorded in `docs/process/DECISIONS.md`.
2. Benchmark numbers are measured, never estimated, and labelled with the hardware they ran on.

Sizing (design assumption, not a measurement): ~300 QPS average, ~900 peak. The binding constraints are concurrency (~4K requests in flight at peak, because each LLM call takes seconds) and tokens (~450K tokens/s against provider limits), not request rate.

## Tech stack

Java 21 + Spring Boot 3 (virtual threads on) · Redis · PostgreSQL + pgvector · Resilience4j · Ollama (local fallback `llama3.2:1b`, embeddings `nomic-embed-text`) · Prometheus + Grafana (dashboard JSON in repo, behind a compose profile) · Docker Compose · JUnit 5 + Testcontainers · k6 against a WireMock mock provider.

## Build plan

| # | Milestone | Task |
|---|---|---|
| t00 | Setup | Local setup (`scripts/check_setup.sh`), repo with `CLAUDE.md` |
| t01 | Gateway core | Spring Boot project, OpenAI-compatible `/v1/chat/completions` proxying Claude (SSE streaming, request log to Postgres, docker-compose) |
| t02 | Gateway core | Per-tenant token-based rate limiting (Redis), 429 with `Retry-After`, token cost attribution per tenant |
| t03 | Gateway core | Provider fallback with timeouts and retries (circuit breaker, Ollama fallback) |
| t04 | Gateway core | Prometheus metrics: latency, tokens, errors per tenant (Grafana dashboard JSON) |
| t05 | Caching, routing | Response caching (exact-match, Redis) with TTL |
| t06 | Caching, routing | Simple model router (cost / latency rules) |
| t07 | Caching, routing | Output validation (JSON schema) for model responses |
| t08 | Caching, routing | README: architecture diagram + load-test numbers (k6) |
| t09 | RAG endpoint | Document ingestion and chunking; embeddings into pgvector |
| t10 | RAG endpoint | Hybrid search: full-text + vector, reciprocal rank fusion |
| t11 | RAG endpoint | Incremental indexing (re-embed only changed docs by content hash) |
| t12 | ACLs and evals | Chunk-level ACL filtering at retrieval time |
| t13 | ACLs and evals | Eval set (~30 questions): retrieval recall@k and answer scoring |
| t14 | ACLs and evals | Final README; tag v1.0 |

Gates: after t08 the gateway is demo-able with measured numbers. After t14, v1.0 is tagged.

**Stretch (only after v1.0):** semantic cache (embedding similarity, configurable threshold), async bulk path on Kafka (batch size + linger, DLQ, bulk vs sync throughput), public deploy with a hard rate limit.

**Out of scope:** web UI, auth beyond API keys, multi-region, fine-tuning, Kubernetes, microservice split, running a 7B+ local model.

## Numbers to measure (README benchmark table)

Gateway overhead p50/p95/p99 (gateway on vs off against a mock provider) · cache-hit p99 (target < 200 ms) · cache hit rate · cost per 1k requests with/without cache and routing · fallback success with the primary killed · 429 correctness · recall@k per chunking setting · answer score baseline vs hybrid · zero ACL leakage. Every number is labelled with the hardware it was measured on.

## Hardware constraints (8 GB MacBook Air)

| Rule | Why |
|---|---|
| OrbStack instead of Docker Desktop, VM capped at 4 GB | Lighter VM on 8 GB |
| Default `docker compose up` = Postgres (`pgvector/pgvector:pg16`) + Redis (`redis:7-alpine`) only | ~0.5 GB total |
| Prometheus + Grafana behind `--profile obs`, used for t04 and t08 | ~300 MB saved most days |
| App runs with `-Xmx512m`, virtual threads on | Keeps the JVM small; virtual threads handle thousands of in-flight streams |
| Ollama only when needed: `llama3.2:1b` for the fallback (t03), `nomic-embed-text` for embeddings (t09, so `vector(768)`) | A 7–8B model needs 5+ GB and won't fit |
| Load tests (t08) hit a WireMock mock provider with fixed latency | Measures gateway overhead honestly and uses almost no memory |
| Answer scoring (t13) uses a small hosted model via API with a spend limit | Local judge models don't fit |

The fallback path is what matters, not the fallback model's quality, so a 1B local model is enough to prove failover works.
