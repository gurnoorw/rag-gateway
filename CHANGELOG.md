# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The `1.0.0` entry stays unreleased until the `v1.0` tag (D-55): `build.gradle.kts` keeps `0.1.0-SNAPSHOT` until the
release commit sets `1.0.0`, and the date is added then. The decision ids in brackets point to
`docs/process/DECISIONS.md`; task ids (t00–t14) to `docs/process/WORK_PLAN.md`.

## [1.0.0] - Unreleased

First release: an OpenAI-compatible LLM gateway with a retrieval-augmented answer endpoint on top, in Java 21 and
Spring Boot 3.5, with Postgres (pgvector) and Redis.

### Added

#### Foundation (t00)
- Design documents (HLD, LLD, concepts, roadmap), the decision log for the 27 conflicts between them (C1–C27) and
  the technical decisions, and a two-developer work plan with team rules (D-25).
- Gradle 8.14.3 wrapper with a pinned version catalog on the Spring Boot 3.5.16 BOM (D-01, D-02).
- Contract types, the OpenAI-style error model, Testcontainers test bases, a fake provider and a `V1` migration
  enabling `vector` (D-12, D-13).
- Docker Compose with Postgres (`pgvector/pgvector:pg16`) and Redis (`redis:7-alpine`) by default and
  Prometheus + Grafana under the `obs` profile; `.env.example` with variable names only (D-20, D-32).

#### Chat gateway (t01)
- `POST /v1/chat/completions`, OpenAI-compatible, streaming (SSE) and non-streaming, written directly on the
  request's virtual thread (D-03, D-16, D-26).
- API-key authentication at the edge: SHA-256 key hashes, lookup by hash, a 30 s Caffeine cache; the tenant
  always comes from the key, never from the body (D-22, D-23).
- Claude adapter on the official Java SDK, and a pipeline of filters in front of a single terminal (C3, D-04,
  D-05, D-06).
- Request id and gateway response headers naming the provider and model actually used (D-15, D-28).
- One request-log row per request with tokens and cost, written after the response is closed; a failed insert
  is a metric and a structured log line, never a client error (C7, D-31).
- Edge statuses: 499 on early client cancel, upstream 401/403 mapped to 502 (D-30).

#### Rate limits, budgets and usage (t02)
- Per-tenant token bucket in Redis, one Lua script using Redis `TIME`; reserve the estimate before the call and
  settle to billed usage after, exactly once (C5, C23, C24).
- In-memory fallback bucket when Redis is unavailable (fail open, one counter) (D-17, D-36).
- Monthly budget ledger with a price table from configuration; check before, add actual after (C22, D-08).
- `GET /v1/usage` for a tenant's tokens and cost (D-33); failures before the first token billed by cause (D-34).

#### Resilience (t03)
- First-token and idle timeouts in both provider adapters; an Ollama adapter for the local fallback model.
- Fallback router with a Resilience4j circuit breaker and retry per provider, full-jitter backoff, retries only
  before the first token, and a fallback chain of Claude then Ollama `llama3.2:1b` (C6, C20, D-07, D-37).

#### Observability (t04)
- Request metrics with bounded label values, explicit histogram buckets, per-attempt tokens and no tenant label
  on histograms; gateway overhead excludes provider time and deliberate waits (D-19, D-38, D-39).
- Prometheus scrape configuration, a provisioned Grafana dashboard and checks for the dashboard and targets.

#### Response cache (t05)
- Per-tenant exact-match cache in Redis for `temperature: 0` requests, keyed after model selection
  (`cache:v1:` prefix), with single-flight filling under a token-owned lock; Redis failures fail open (C21, D-40,
  D-41).

#### Model routing and output validation (t06, t07)
- `"model": "auto"` resolved by ordered rules on request size and schema, restricted to the tenant's allowed
  models (D-42).
- JSON-schema output validation (draft 2020-12, no remote schema loading): buffered, validated and retried once
  when a schema is requested; structural checks on every streamed answer (C8, D-10, D-43, D-44).

#### Benchmarks (t08)
- k6 scenarios against WireMock mock providers with fixed latency, a run-all script and a summary file contract
  (`bench/summary.schema.json`) with a mandatory hardware label (C10, C14, D-45).
- README with designed-for and measured tables kept apart, a Mermaid architecture diagram, a number checker
  holding every README number to a committed results file, a demo script and a quickstart check (C27).
- Redis skip window after a failure, shared by the limiter, the budget and the cache; per-instance in-flight
  limit answering 503 `overloaded` (D-46).

#### RAG ingestion (t09)
- `PUT` and `GET /v1/rag/collections/{collection}/documents/{docId}`: the document and an outbox row in one
  transaction, 202, indexing state on `GET`; admin keys only (C4, D-47).
- Tenant and ACL groups on documents and chunks; groups and an admin flag on API keys (C16, C17).
- Heading-aware chunker, embedders (Ollama `nomic-embed-text` with task prefixes, 768 dimensions; a deterministic
  fake for tests), a bounded indexing worker and a pinned demo corpus fetched by script, never committed (C1,
  D-18, D-48).

#### RAG query (t10)
- `POST /v1/rag/query`: hybrid retrieval combining Postgres full-text search (`ts_rank_cd`, OR between terms) and
  pgvector HNSW search with reciprocal rank fusion, tenant and ACL filters inside both queries (C18, C19, D-49).
- Answers with source citations through the same chat pipeline, a delimited prompt with source-tag defusing, a
  no-sources answer without a provider call and keyword-only degradation when the embedder is down (D-49, D-50).

#### Incremental indexing and ACL hardening (t11, t12)
- Three PUT outcomes under a row lock (unchanged, groups-only update, re-index), fenced job updates, retries with
  backoff, dead jobs and an idempotent `DELETE` (D-51, D-52).
- Chunk-level ACL filtering with iterative index scans, an independent leakage audit and a leakage test suite.
- Outbox indexes (`V5`), dead-job and exact-fallback dashboard panels and alert rules (D-52).

#### Evaluation (t13)
- Eval set with gold passages, retrieval metrics (recall@k, MRR, nDCG@10) across modes and chunk sizes, an ACL
  leak check and schema-checked results files (D-53).
- Answer judge through its own client with a per-call spend cap and a dry run; deterministic answer checks.
- Seed script for the eval tenant's gateway key (D-55).

#### Release (t14)
- Owner runbook for the steps that need the laptop, real models or an API key (`docs/RUNBOOK.md`).
- `scripts/release_check.sh`, one command for every release check: fresh-clone build, README numbers in
  release mode, history secrets scan (`scripts/secrets_scan.sh`), decision index (`scripts/check_decisions.py`),
  Mermaid, licence, version and changelog, wording (`scripts/wording_scan.sh`) (D-55).
- Release checklist (`docs/process/RELEASE_CHECKLIST.md`) and this changelog.

### Fixed
- API keys are loaded outside the Caffeine compute lock (t01).
- Claude response headers are awaited on a virtual thread instead of the HTTP client's capped dispatcher; a
  provider 404 is a configuration fault that moves to the next provider; the Ollama body is drained after `done`
  (t03, D-37).
- After a cache fill that cannot be stored, waiters call the provider in parallel instead of waiting out the lock
  (t05, D-41).
- An empty answer rejected by the structural check is billed with its reported usage (t07, D-44).
- Chunks are swapped only for the stored document version; full worker batches drain the backlog; headings
  carried into a chunk are bounded (t09, D-48).
- The vector leg re-runs exactly when the HNSW pass returns too few rows (t10, D-49).
- The outbox claim runs its locking sub-select once, through a materialised CTE, so a claim never takes more than
  its limit (t13, D-47 amendment).
- Keyword ties are broken by document and ordinal, not by generated chunk id (t13, D-54).

### Known limitations
- Laptop benchmark rows, vector and hybrid retrieval scores and answer scores are pending the owner's run with
  `nomic-embed-text` (`docs/RUNBOOK.md`); the README marks them "pending laptop run" and carries no number.
- See the README "Limitations" section for the design-level limits.
