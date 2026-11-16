# Architecture decisions

Status: accepted, 2026-11-16, before t01. Each entry is an ADR: context (which documents say what), decision, reason, alternatives rejected, consequences.

**Precedence when documents disagree:** `CLAUDE.md` overrides > this file > `docs/LLD.md` > `docs/HLD.md` > `docs/CONCEPTS.md`. Where an entry changes an LLD signature it says so; the full list of contract changes is D-14. The contracts themselves are compiling code under `src/main/java/dev/raggateway` with javadoc.

Part 1 settles the 27 conflicts found in the requirements review (C1–C27). Part 2 records the further technical decisions (D-01–D-25). Entries marked **Overrides the requirements review** depart from the recommendation made there, with the reason.

## Index

| id | Topic | Decision in one line |
|---|---|---|
| C1 | Embedding size | 768 (`vector(768)`), from config |
| C2 | Sizing | 300 avg / 900 peak QPS, ~4K in flight, ~450K output tokens/s, all labelled "design assumption" |
| C3 | Pipeline order | edge auth → body checks → request validation → model selection → cache → rate limit + budget → terminal (output validation → fallback router → provider) |
| C4 | Ingestion API | `PUT .../collections/{c}/documents/{id}` → 202, transactional outbox |
| C5 | Rate-limit algorithm | token bucket, Redis + one Lua script, reserve then settle |
| C6 | Circuit breaker / retry | Resilience4j CircuitBreaker + Retry; first-token timeout in the adapter; no hand-written breaker in `src/main` |
| C7 | Request/cost log | one insert per request after the response is closed; failure = metric + structured log line |
| C8 | Validation vs streaming | schema requested → buffer, validate, retry once, then send; no schema → stream |
| C9 | Log partitioning | none; one table, index `(tenant_id, created_at)` |
| C10 | Load tool | k6 |
| C11 | Repo visibility | public from t00 |
| C12 | Task count | t00–t14, 15 ids |
| C13 | Process | agents build; owner acceptance step per task with private notes (not committed) |
| C14 | Benchmark hardware | "Measured on" column on every row |
| C15 | Error transport | before first token: HTTP status + JSON; after: SSE error event |
| C16 | ACL groups | on the API key, with an admin flag |
| C17 | Tenant isolation in RAG | `tenant_id` on documents and chunks, in every query |
| C18 | "BM25" | Postgres full-text `ts_rank_cd`; never called BM25 |
| C19 | Keyword query | OR between terms |
| C20 | Fallback chain | Claude, then Ollama `llama3.2:1b` |
| C21 | Caching a fallback answer | never |
| C22 | Budget check | check before, add actual after; bounded overshoot |
| C23 | Settle exactly once | `finally` after a blocking `chain.next`; disconnect cancels upstream |
| C24 | Limiter clock | Redis `TIME` inside Lua |
| C25 | Schedule dates | no build impact |
| C26 | Demo page | none |
| C27 | Designed-for vs measured | two separate README tables |
| D-01 | Build tool | Gradle 8.14.3, Kotlin DSL, wrapper |
| D-02 | Versions | Spring Boot 3.5.16 BOM + version catalog |
| D-03 | Web stack | Spring MVC on virtual threads, response written directly |
| D-04 | HTTP clients | Claude: official Java SDK; Ollama: JDK HttpClient |
| D-05 | Claude API version | 2026-10-21; sampling params never sent to Claude |
| D-06 | Claude adapter details | text deltas only, first-token = first content event, refusal = `content_filter` |
| D-07 | Resilience4j modules | circuitbreaker, retry, micrometer only |
| D-08 | Model ids and prices | config, with source and date |
| D-09 | Persistence | plain JDBC (`JdbcClient`) + Flyway, pre-assigned migration numbers |
| D-10 | JSON-schema validator | networknt 1.5.9, draft 2020-12 |
| D-11 | Token estimation | character-ratio estimator behind `TokenEstimator` |
| D-12 | Test strategy | unit + Testcontainers + WireMock, one `./gradlew test` |
| D-13 | Package layout | by feature under `dev.raggateway` |
| D-14 | LLD contract changes | table of every signature change |
| D-15 | Request id and headers | generated id, `X-Request-Id`, gateway headers |
| D-16 | OpenAI surface | what is accepted, what is rejected |
| D-17 | Redis settings | 50 ms timeout, reject when disconnected, AOF, `volatile-lru` |
| D-18 | Embedder in this environment | deterministic fake embedder, labelled |
| D-19 | Metrics | fixed names, bounded labels, histograms without tenant |
| D-20 | Image pins | compose and tests use the same tags |
| D-21 | What cannot be verified here | Ollama, live Claude, laptop numbers: how each is covered |
| D-22 | API-key storage | SHA-256, no salt, lookup by hash |
| D-23 | Key lookup cache | Caffeine, 30 s |
| D-24 | Eval harness and judge | separate source set; judge through its own SDK client |
| D-25 | Delivery process | two-developer rounds, worktrees, strict merge order |
| D-26 | Request mapping beyond D-16 | sampling ranges, ≥1 conversational message, `max_completion_tokens` wins, system messages joined first |
| D-27 | Spring MVC errors and unknown paths | keep MVC status with the OpenAI body; `/v1/**` authenticates before routing |
| D-28 | Which provider the headers name | terminal announces each attempt on the context before streaming |
| D-29 | Claude key header | explicit `X-Api-Key`; SDK `apiKey` still set |
| D-30 | Edge statuses | 499 on early cancel; upstream 401/403 → 502; model without adapter → 400 in t01 only; blank key starts with a warning |
| D-31 | Request latency end point | frozen at `RequestContext.finish`, not at log time |
| D-32 | Environment variable names | `GATEWAY_CLAUDE_*`, `GATEWAY_OLLAMA_BASE_URL`, not the `ANTHROPIC_*` names |
| D-33 | t02 settlement and usage details | settle from billed usage of all attempts; no extra debit on under-estimate; rejected requests logged; `/v1/usage` contract |
| D-34 | Billing a failure before the first token | HTTP error status → zero usage; timeout or broken connection → estimated input |
| D-35 | Integration test bases | shared-Redis base separate from own-Redis base; unregistered Redis fails loudly |
| D-36 | t02 review rulings | too-large before budget; budget 0 blocks; unknown model priced 0 with ERROR; one fail-open counter |
| D-37 | t03 review rulings | exhausted chain = 503; 401/403/404 are configuration faults that move on; Claude header wait on a virtual thread; Ollama drain; test-base breaker reset |
| D-38 | Request metrics details | bounded label values; explicit histogram buckets; per-attempt tokens; zero series at startup; no duplicates of earlier metrics |

---

## Part 1: conflicts C1–C27

### C1. Embedding size
- **Context:** HLD says "1,024-dim float vector", LLD has `vector(1024)`; `CLAUDE.md` overrides: 768, from `nomic-embed-text`.
- **Decision:** 768. `Embedder.dimensions()` returns it; the column is `vector(768)`; the Ollama adapter rejects any other length (AC-t09-8).
- **Reason:** the override wins and the model produces 768 values.
- **Rejected:** 1024 (wrong for the model in use).
- **Consequences:** changing the embedding model means a new column size and a full re-index (t11 notes).

### C2. Sizing numbers
- **Context:** the project brief (newer) says ~300 QPS average, ~900 peak, ~4K requests in flight, ~450K tokens/s; the HLD says 1,000 peak and ~10,000 concurrent.
- **Decision:** 300 average, 900 peak, ~4K in flight (900 req/s × ~4.5 s), ~450K output tokens/s (900 × ~500). The HLD table stays as written; this entry wins.
- **Reason:** the newer source; the conclusion (concurrency and tokens bind, not request rate) is the same for both.
- **Rejected:** keeping two sets of numbers in public docs.
- **Consequences:** the README shows these under "designed for", never as measurements (C27). t08 tests what one laptop-sized instance actually holds (scenario 7).

### C3. Pipeline order
- **Context:** LLD: `AuthFilter → CacheFilter → RateLimitFilter → ValidationFilter → FallbackRouter`, "so cache hits are free". CONCEPTS path 1 and the project brief: auth → rate limit → route → cache → provider → validate. The LLD has no model routing step and puts "validation" before the provider with unclear meaning.
- **Decision:** at the HTTP edge: API-key auth (401) → body mapping (400). Then the filters, in `FilterOrder`: `REQUEST_VALIDATION` (400: `max_tokens` cap, schema compiles) → `MODEL_SELECTION` (resolve `auto`, allowed models; 400/403) → `CACHE` (a hit answers here) → `RATE_LIMIT` (budget check, token reservation, settle). Then the single `Terminal`, which is itself layered: output validation (t07) → fallback router with retry and breakers (t03) → provider adapter. Request log and metrics run after the response is closed, as `RequestCompletionListener`s.
- **Reason:** cheap rejections first, so a 400 costs nothing; model selection before the cache so the key holds the resolved model, not `auto` (AC-t06-8); the cache before the limiter because the limit protects provider tokens and money and a hit spends neither (the LLD's reason, kept); output validation needs to call the provider a second time, which a filter cannot do (`FilterChain.next` may be called once), so it decorates the terminal. "Model selection" (which model) and the "fallback router" (which provider, on failure) are different steps with different names.
- **Rejected:** CONCEPTS order (rate limit before cache): every hit would consume TPM it never uses. Output validation as a filter: it would need to re-enter the chain.
- **Consequences:** cache hits are not limited per tenant: a tenant can replay a cached prompt without limit. Accepted: hits cost Redis reads only. A cheap request-count limit in front of the cache is the fix if that matters (not built).

### C4. Ingestion API
- **Context:** HLD/LLD: `PUT /v1/rag/collections/{collection}/documents/{docId}` → 202 with an outbox. CONCEPTS ingestion line and the project brief: `POST /v1/rag/documents`, reads as synchronous.
- **Decision:** HLD/LLD. The document row and an outbox row are written in one transaction; a scheduled worker indexes. t09 builds PUT + outbox + worker happy path; t11 adds hash skip, lease/retry/dead, stale-job skip and DELETE.
- **Reason:** PUT is idempotent by URL; embedding takes seconds per document on the laptop and must not hold an HTTP request; the outbox guarantees no saved document without a job.
- **Rejected:** synchronous POST (slow requests, partial failure between save and index).
- **Consequences:** after a 202 the caller knows the document is saved and queued, not indexed; `GET` on the same URL returns the indexing state.

### C5. Rate-limit algorithm
- **Context:** CONCEPTS concept table: "sliding-window limiter… weighted by cost". CONCEPTS path 1, HLD deep dive 1 and LLD: token bucket in Redis + Lua.
- **Decision:** token bucket, one Lua script, reserve `estimated input + max_tokens` before the call, settle to actual usage after.
- **Reason:** allows a controlled burst up to capacity and needs two fields per tenant; atomic in one script.
- **Rejected:** sliding-window log (one entry per request, memory grows with traffic); fixed window (2× burst at the edge).
- **Consequences:** fairness depends on `max_tokens`: a caller with a large `max_tokens` reserves more than it uses until settlement.

### C6. Circuit breaker and retry
- **Context:** the stack lists Resilience4j as fixed. The LLD shows a hand-written `CircuitBreaker` and retry loop "what such a breaker does inside", and says "the service uses Resilience4j".
- **Decision:** Resilience4j `CircuitBreaker` (one per provider) and `Retry` (one per provider) in `src/main`; the hand-written breaker is not shipped. Details and the streaming rule are D-07.
- **Reason:** the stack is fixed; a second, unused breaker would be dead code. The behaviour that is ours stays ours: retry only before the first token (retry predicate), the two timeouts, fallback on retryable errors only.
- **Rejected:** shipping both; shipping only the hand-written one.
- **Consequences:** the LLD state machine remains the explanation of what Resilience4j does; tests assert behaviour (AC-t03-4/5) with Resilience4j's injectable `Clock`.

### C7. Request and cost logging
- **Context:** HLD deep dive 8: "written async in batches (buffer in memory, flush every second or 500 rows)". The CONCEPTS trade-off table leaves sync vs async open. The requirements review recommended one plain insert per request.
- **Decision:** one `INSERT` per request, on the request's own virtual thread, **after the response has been completed and closed** (SSE `[DONE]` written and the output stream closed, or the JSON body written with its length). It runs in the `RequestLogWriter` completion listener. Postgres connection timeout is 2 s (Hikari). The insert time is a timer metric (`gateway.requestlog.write`).
- **Failure behaviour:** if the insert fails or times out, the request is not affected (the client already has the full response). The writer increments `gateway.requestlog.write.failures` and writes one WARN log line with the request id, tenant, model, provider, token counts, cost and status (no prompt text), so the row can be reconstructed from logs. Budgets are enforced from Redis (C22), not from this table, so a lost row cannot cause an overrun; it can make `GET /v1/usage` under-report.
- **Reason:** closing the response first takes the insert off the client's latency path, which is the only gain batching would bring at this load, without a buffer that loses rows on a crash and needs shutdown draining. With virtual threads, a request thread waiting on a connection is cheap; the pool size (10) bounds database concurrency. t08 measures the insert time so the claim is backed by a number.
- **Rejected:** in-memory batch buffer (HLD): adds a loss window, a bounded queue and back-pressure policy, and graceful-shutdown logic, for no measured need at laptop load; it is the right answer when the per-row insert or connection count shows up in t08 or at the 900 QPS design point, and that is how it is described in the README. Writing the row before the response ends: adds the insert to client latency. A message queue: not in the stack.
- **Consequences:** at the design point a single primary takes ~900 single-row inserts/s, which Postgres handles but which holds connections; batching (or `COPY`) is the documented next step. Requests rejected at the edge with 401 or a malformed body have no tenant context and no log row; everything that reaches the pipeline gets exactly one row.

### C8. Output validation vs streaming
- **Context:** the plan says "output validation for every model response"; SSE delivers tokens as they arrive, so invalid output would already be sent.
- **Decision:** with `response_format` of type `json_schema`: buffer the whole response, validate, and send only if valid (as JSON, or as one burst of SSE chunks if `stream:true`). Invalid → retry once; still invalid → 502 `invalid_model_output` with a short error summary. Without a schema: stream token by token; apply structural checks only (a finish reason and usage must be present; empty content with finish reason `stop` is malformed). A structurally broken response before any token was sent is a retryable provider failure. A schema request cut off by `max_tokens` is invalid. Tokens of failed attempts are charged; invalid output is never cached.
- **Refines the requirements review:** "non-empty content" alone would wrongly fail a legitimate empty answer that ended by `length` or `content_filter`; emptiness is checked only with finish reason `stop`.
- **Reason:** tokens cannot be un-sent; buffering turns a validation failure into a before-first-token failure where retry is legal. Structured-output callers are programs that do not need token streaming.
- **Rejected:** validating after streaming (the client already has the bad output); JSON repair (hides model errors).
- **Consequences:** a schema request has no streaming benefit; its time to first byte equals total generation time.

### C9. Request-log partitioning
- **Context:** HLD: "partitioned by day", "drop old partitions". Laptop scale writes thousands of rows.
- **Decision:** one unpartitioned table, index on `(tenant_id, created_at)`.
- **Reason:** partitioning adds DDL and maintenance with no benefit at this size.
- **Rejected:** daily partitions.
- **Consequences:** retention would be a `DELETE`; partition-by-day is the documented step at the HLD's 26M rows/day.

### C10. Load tool
- **Context:** CONCEPTS: "k6 or Gatling"; `CLAUDE.md`: k6.
- **Decision:** k6, through the `grafana/k6` image in this environment and a local `k6` on the laptop.
- **Reason / rejected:** override; Gatling rejected.
- **Consequences:** none.

### C11. Repository visibility
- **Context:** CONCEPTS open question; the progress tracker reads the repository without logging in.
- **Decision:** public from t00. Only the owner creates and pushes it.
- **Reason:** the tracker needs public raw access.
- **Rejected:** private until v1.0.
- **Consequences:** nothing personal or secret may be committed; `.env`, `private/` and `CLAUDE.local.md` are gitignored, and a secrets scan runs before every push (AC-t00-2, AC-t14-7).

### C12. Task count
- **Decision:** 15 ids, t00 (setup) and t01–t14 (build). Use "t00–t14" everywhere.

### C13. Process
- **Context:** `CLAUDE.md` asks for an explanation and a go-ahead before each task; this build is done by an agent team (architect, two developers, reviewer).
- **Decision:** agents build in the order t01–t14. Each task ships tests, a commit series and private task notes (not committed). The owner runs an acceptance step per task afterwards (reads the notes, runs the demo command, may reject the design). Agents never write `.tracker/`, never mark a task done, never claim a demo was seen. This file is the "say so before writing the code" record for every deviation.
- **Overrides the requirements review:** the review placed the per-task notes in `docs/defence/`. They are kept outside the repository instead (owner's requirement); public documents refer to them only as private task notes.
- **Consequences:** an acceptance criterion that names those notes (AC-t14-4) is checked locally by the owner, not by CI.

### C14. Benchmark hardware
- **Decision:** every README benchmark row has a "Measured on" value. Numbers from this environment read "cloud container, 4 vCPU / 15 GB, gateway + WireMock + k6 on one host, run <date>". Laptop rows are added by the owner. Rows not yet run read "pending laptop run" and carry no number.
- **Reason:** numbers are measured, labelled, and traceable to a committed result file (AC-t08-2).

### C15. Where errors go (HTTP status vs SSE event)
- **Context:** HLD API: 429 with JSON body and `Retry-After`. LLD controller always returns an `SseEmitter` and reports 401/429 through `onError` after the response is already a 200 event stream.
- **Decision:** any failure **before the first token** is a real HTTP status (400, 401, 403, 413, 429, 502, 503) with a JSON body `{"error":{"message","type","code","request_id"}}` (`ErrorResponse`). A failure **after the first token** is one SSE event `data: {"error":{...}}`, after which the stream ends without `[DONE]`. `stream:false` returns a normal `chat.completion` JSON or an error status. The client-facing sink decides from whether it has committed the response.
- **Reason:** OpenAI SDKs map status codes to typed exceptions and read `error.message/type/code`; a 200 stream carrying an error breaks them. The OpenAI Python SDK raises on a `data:` event whose JSON has an `error` field, so the mid-stream form is also SDK-compatible.
- **Rejected:** LLD `SseEmitter` (commits a 200 before the pipeline runs); a named `event: error` (some SDKs ignore unknown event names).
- **Consequences:** the controller writes the servlet response itself (D-03) and sets headers lazily at the first byte.

### C16. Where ACL groups come from
- **Context:** LLD `Tenant(…, Set<String> aclGroups)`; CONCEPTS: "each user seeing only the documents they're allowed to"; auth is API keys only.
- **Decision:** groups and an `admin` flag belong to the API key (`ApiKeyPrincipal`). A tenant has one or more keys. Retrieval uses the key's groups; only admin keys write documents.
- **Reason:** one group set per tenant would make every caller in a tenant see the same chunks; keys are the only identity available.
- **Rejected:** groups on the tenant; a user table (out of scope: auth beyond API keys).
- **Consequences:** `Tenant` loses `aclGroups` (D-14). The demo seed has two tenants and three keys with different groups.

### C17. Tenant isolation in RAG
- **Context:** HLD NFR "zero chunks leaked across ACL boundaries"; LLD `documents`/`chunks` have no tenant column.
- **Decision:** `tenant_id` on `documents` and `chunks` (copied onto chunks like the ACL groups); a document is identified by the unique key `(tenant_id, collection_id, doc_id)` and chunks reference its surrogate id; every retrieval query has `tenant_id = :tenant AND collection_id = :collection AND acl_groups && :groups`. `RetrievalQuery` carries the tenant.
- **Reason:** tenant A's group `eng` and tenant B's group `eng` would otherwise match each other's chunks.
- **Rejected:** a collection-per-tenant naming convention (one bug away from a leak); Postgres row-level security (adds a session-variable dependency to every connection; noted as an alternative in t12).
- **Consequences:** document ids are unique per tenant and collection, not globally; chunk ids are generated (`uuid`), not `docId:ordinal`.

### C18. "BM25"
- **Decision:** the keyword leg is Postgres full-text search ranked by `ts_rank_cd` (cover density). Public text says "Postgres full-text ranking", never "BM25".
- **Reason:** `ts_rank_cd` has no IDF and no BM25 length normalisation; calling it BM25 would be wrong.
- **Rejected:** a BM25 extension (more moving parts for a 300-document corpus).

### C19. Keyword query semantics
- **Context:** LLD uses `websearch_to_tsquery`, which ANDs the terms; the keyword leg exists to catch exact terms.
- **Decision:** OR between the stemmed terms: the `tsquery` is built from `plainto_tsquery('english', :q)` with `&` replaced by `|`; ranked by `ts_rank_cd`, top 50.
- **Reason:** with AND, a natural-language question needs every non-stop word in one chunk; most questions would return no keyword rows and hybrid would silently become vector-only.
- **Rejected:** `websearch_to_tsquery` (AND); `phraseto_tsquery` (stricter still).
- **Consequences:** recall goes up, precision per keyword hit goes down; RRF and the vector leg compensate. Measured in t13.

### C20. Fallback chain length
- **Decision:** two providers: Claude (primary), Ollama `llama3.2:1b` (fallback), as an ordered config list.
- **Reason:** a third adapter adds no new idea; the local model proves failover without cost.
- **Consequences:** the fallback answer is weaker; it is never cached (C21) and is visible in the response (`model` field, `X-Gateway-Provider`).

### C21. Caching a fallback answer
- **Context:** LLD `CacheFilter` stores whatever came back; HLD says a fallback model may answer worse.
- **Decision:** cache only responses served by the provider and model that model selection chose (`RequestContext.fallbackUsed()` is false). Never cache errors, partial streams, invalid output, or fallback answers.
- **Reason:** otherwise a 1B-model answer would be served as the primary answer for the whole TTL after a short outage.
- **Consequences:** during an outage, identical requests keep calling the fallback; acceptable.

### C22. Budget check
- **Context:** HLD: budget "checked the same way" (reserve); LLD: check before, add after.
- **Decision:** LLD approach: check `spent < budget` before the call; add the actual cost (micro-USD, integer `INCRBY`) after.
- **Reason:** reserving money needs a price estimate per request for little gain against a monthly budget.
- **Consequences:** bounded overshoot: at most (requests in flight for that tenant) × (max cost of one request). AC-t02-11 tests the bound.

### C23. Settlement exactly once, refund on failure, client disconnect
- **Context:** LLD "points to say": refund on errors too; LLD code refunds only in `onComplete`. HLD: cancel upstream when the client disconnects; LLD has no cancel path.
- **Decision:** `FilterChain.next` is a blocking call that returns only when the request has finished. Filters that hold a reservation settle in a `finally` block after `chain.next`, which runs exactly once on every exit: success, error, exception, disconnect. Settlement uses `RequestContext.billedUsage()` (sum over all attempts): a failed call with no usage refunds the full reservation; a disconnect charges the tokens produced so far. Disconnect path: the client sink's write fails → it throws `ClientDisconnectedException` → the exception travels through the sink decorators into the provider adapter → the adapter closes the upstream response (cancelling the provider call) and rethrows → the terminal records a `CLIENT_CANCELLED` attempt with estimated usage (`usageEstimated=true`) → filters settle in `finally` → the pipeline writes the log row with status `client_cancelled`.
- **Reason:** with blocking code on virtual threads, `finally` is the simplest exactly-once mechanism; callback-based settlement (LLD) misses exceptions and disconnects.
- **Rejected:** settlement in `ForwardingSink.onComplete/onError` (misses thrown exceptions); an idempotency flag on every sink (unneeded once `finally` is the rule).
- **Consequences:** usage of a cancelled stream is estimated (input estimate + streamed text), not provider-reported, and flagged as such in the log. Disconnect is detected on the next write; a client that disconnects while the gateway waits for the first token is detected at the first token (bounded by the first-token timeout).

### C24. Limiter clock
- **Decision:** the Lua script reads `redis.call('TIME')`; the app passes no timestamp. Tests control timing through `BucketSpec`: a slow refill rate makes outcomes independent of test speed, and a fast one makes `Retry-After` short enough to wait for (AC-t02-3).
- **Reason:** one clock for all gateway instances; no drift.
- **Rejected:** app-server `now_ms` (LLD); a test-only clock argument in the script (test code in production Lua).
- **Consequences:** Redis 7 replicates script effects, so `TIME` inside a script is allowed.

### C25. Schedule dates
- **Context:** planning notes disagree on calendar dates.
- **Decision:** no build impact; tasks keep their order.

### C26. Demo page
- **Decision:** no demo page. The README shows `curl` and one OpenAI SDK example.
- **Reason:** in no task; scope creep.

### C27. Designed-for vs measured
- **Decision:** two README tables: "designed for" (assumptions, C2 and HLD targets) and "measured" (with hardware and date). Nothing from the first is phrased as a result.

---

## Part 2: technical decisions

### D-01. Build tool: Gradle 8.14.3 (Kotlin DSL) with the wrapper
- **Context:** Gradle 8.14.3 and Maven 3.9.11 are installed; `.gitignore` already covers Gradle.
- **Decision:** Gradle with the committed wrapper (`./gradlew`), Kotlin DSL, a version catalog (`gradle/libs.versions.toml`), `-Xlint:all -Werror`. One `test` task runs unit and integration tests (`./gradlew test`, required by every task). An `eval` source set holds the t13 harness so it never ships in the app jar.
- **Rejected:** Maven (equally capable; Gradle's catalog and source sets make the eval split and version pinning shorter).
- **Consequences:** contributors run `./gradlew`, never a system Gradle.

### D-02. Versions
- **Decision:** Spring Boot 3.5.16 (latest 3.x; its BOM pins Jackson 2.21.4, Flyway 11.7.2, Lettuce 6.6.0, Micrometer 1.15.12, Testcontainers 1.21.4, PostgreSQL JDBC 42.7.11). Not managed by Boot, pinned in the catalog: Resilience4j 2.4.0, Anthropic Java SDK 3.3.0, networknt json-schema-validator 1.5.9, pgvector-java 0.1.6, WireMock standalone 3.13.2. Java 21 toolchain.
- **Reason:** the stack fixes Spring Boot 3; 3.5 is its last line. Every version is at least two weeks old at the time of writing.
- **Rejected:** Spring Boot 4.x (not the fixed stack; Jackson 3 and other migrations would touch every task).
- **Consequences:** moving to Boot 4 is a post-v1.0 item. The Anthropic SDK brings OkHttp 4.12 and the Kotlin stdlib (Gradle resolves Kotlin to 2.1.x).

### D-03. Web stack: Spring MVC on virtual threads, response written directly
- **Context:** HLD deep dive 4 chooses virtual threads over WebFlux. The LLD controller hands work to an executor and returns an `SseEmitter`.
- **Decision:** Spring MVC (Tomcat) with `spring.threads.virtual.enabled=true`, so each request already runs on its own virtual thread. The chat controller takes the `HttpServletResponse` and runs the pipeline on that thread. The client sink commits status and headers lazily at the first byte (C15). Tomcat `max-connections` is raised to 10,000.
- **Reason:** no executor hand-off, no async servlet timeouts, errors before the first token can still choose their status, and disconnects surface as a failed write on the same thread that owns the upstream call.
- **Rejected:** `SseEmitter` + executor (LLD; commits a 200 early); WebFlux (harder to write and reason about; same scaling for I/O-bound work).
- **Consequences:** blocking inside `synchronized` pins a carrier thread on Java 21; code under our control holds no lock across I/O (`ReentrantLock` where needed). This includes `ConcurrentHashMap.compute` and anything built on it, such as Caffeine's `get(key, loader)`: loaders that do I/O run outside the cache (`getIfPresent`, load, `put`), as in the key store (amended in the t01 review). Third-party pinning (e.g. OkHttp inside the Claude SDK) is watched in the t08 open-streams scenario.

### D-04. HTTP clients for providers
- **Decision:** Claude through the **official Anthropic Java SDK** (`com.anthropic:anthropic-java` 3.3.0, `AnthropicOkHttpClient`) configured with `baseUrl` (WireMock in tests), `apiKey`, `timeout(Timeout.builder().connect(..).read(..))` and `maxRetries(0)`; streaming via `client.messages().createStreaming(params)` returning a closeable `StreamResponse<RawMessageStreamEvent>`. Ollama (no official Java SDK) through the **JDK `HttpClient`** forced to HTTP/1.1, native `/api/chat` with NDJSON streaming and `/api/embed` for embeddings.
- **Reason:** the SDK owns the API version header, authentication, SSE parsing and typed errors (`AnthropicServiceException.statusCode()/headers()`), so the adapter is a translation layer only; closing the `StreamResponse` cancels the HTTP call. SDK retries are off because the router owns retries (D-07). The JDK client needs no dependency and blocks cheaply on virtual threads.
- **Rejected:** raw HTTP for Claude (hand-written SSE parser and header handling); Spring `WebClient` (pulls in Reactor for no gain); Ollama's OpenAI-compatible endpoint (usage fields less complete than the native API).
- **Consequences:** WireMock stubs for Claude must be valid Messages-API SSE bodies (`message_start`, `content_block_start`, `content_block_delta`, `message_delta`, `message_stop`).

### D-05. Claude API version 2026-10-21 and sampling parameters
- **Context:** Anthropic Java SDK 3.x sends `anthropic-version: 2026-10-21`. On that version a Messages request that contains `temperature`, `top_p` or `top_k` is rejected with 400 on every model.
- **Decision:** the gateway accepts `temperature` and `top_p` on its OpenAI surface (compatibility, and the cache eligibility rule), forwards them to Ollama, and **never sends them to Claude**. `stop` maps to `stop_sequences`.
- **Reason:** sending them would fail every Claude request; dropping them silently at the edge would break OpenAI clients that always send `temperature`.
- **Rejected:** pinning the SDK to a 2.x release on the older API version (moves away from the current API for a parameter the models no longer honour).
- **Consequences:** for Claude, `temperature: 0` does not make the output deterministic. The cache (t05) keeps the rule "only `temperature = 0` is cached" as the caller's explicit opt-in to receiving a stored answer; the README states that the rule is a contract with the caller, not a determinism guarantee.

### D-06. Claude adapter details
- **Source:** model ids, prices, streaming event shapes and current-model behaviour were taken on 2026-11-16 from the Claude API reference bundled with the build tooling (model table cached 2026-11-03), and SDK method names were checked against the 3.3.0 jars with `javap`. Nothing was written from memory.
- **Decision:**
  - Only `text_delta` content is forwarded as tokens. Current Opus models always think before answering (thinking cannot be disabled; default effort `medium`); thinking blocks arrive with empty text by default and are not forwarded. Thinking tokens are included in `output_tokens` and are billed.
  - The first-token timeout (default 5 s) is measured to the **first content event** (`content_block_start` of any block, thinking included), not to the first text token. Retry legality is still decided by whether a text token reached the client.
  - Stop reasons map to `FinishReason`: `end_turn`/`stop_sequence` → `stop`, `max_tokens` → `length`, `refusal` → `content_filter` (not retried, not a provider failure).
  - Usage: `input_tokens` from `message_start`, cumulative `output_tokens` from the last `message_delta`.
  - The adapter sets no `effort` and no `thinking` field (model defaults) and does not enable the API's server-side refusal fallback (beta).
- **Reason:** measuring to the first text token would time out healthy requests that are thinking and send them to the 1B fallback. Server-side fallback would change the answering model without the gateway knowing, breaking the cache rule (C21) and the "model used" header; the gateway owns fallback.
- **Rejected:** first-text-token timeout; forwarding thinking text.
- **Consequences:** time to first token seen by the client includes thinking time; it is reported, not hidden. A refusal is a normal completion with `finish_reason: content_filter`.

### D-07. Resilience4j modules and retry on a streaming call
- **Decision:** modules `resilience4j-circuitbreaker`, `resilience4j-retry`, `resilience4j-micrometer` only, built programmatically from our own properties (no Spring Boot starter, no annotations/AOP).
  - **Breaker per provider:** count-based window of 20 calls, minimum 20 calls, failure-rate threshold 50 %, 30 s open, 1 trial call half-open, injectable `Clock` (`CircuitBreakerConfig.Builder.clock`). Recorded as failure: retryable `ProviderException`s (timeouts, connection errors, 429, 5xx). Ignored: non-retryable 4xx and `ClientDisconnectedException`.
  - **Retry per provider:** `maxAttempts(2)`; `retryOnException` true only for a retryable `ProviderException` **and** no token sent to the client yet; never for `CallNotPermittedException` (open breaker goes straight to the next provider). Wait from an `IntervalBiFunction`: full jitter, uniform in `[0, min(cap, base × 2^(attempt-1))]` with base 200 ms, cap 2 s; an upstream `Retry-After` replaces it, capped at 2 s.
  - **Composition per provider attempt:** `Retry(CircuitBreaker(adapter.stream(...)))`, then the next provider in the chain.
  - **Timeouts:** in the adapters (connect via the HTTP client; first token and idle gap via a small watchdog that closes the upstream response), not Resilience4j `TimeLimiter`.
- **Reason:** the decorators wrap a blocking call, and a streaming call is only retryable until the first token, which is state the predicate can read. `TimeLimiter` runs the call on another thread and cancels by interrupting it, which does not reliably unblock a socket read; closing the response does.
- **Rejected:** `resilience4j-spring-boot3` + annotations (proxy-based, awkward with per-call streaming state); `TimeLimiter`; `Bulkhead` (load shedding is a documented extension, not built).
- **Consequences:** breaker state is per instance, not shared; acceptable because each instance sees the same provider. Breaker metrics come from `TaggedCircuitBreakerMetrics` (AC-t03-10).
- **Amended in t03 prep:** the move-on rule is `ProviderException.fallbackAllowed()`: retryable failures plus an upstream 401/403 (D-30). A 401/403 is not retried on the same provider and is ignored by the breaker: it is a configuration fault that does not heal with time, so it should be seen in logs and alerts, not hidden behind an open breaker. Superseded in the t03 review (D-37): a 404 (model not pulled, retired model id) is a configuration fault like 401/403 and also moves on. Attempt usage comes from `AttemptUsage.of` (D-34), shared by every terminal.

### D-08. Model ids and prices are configuration
- **Decision:** `gateway.models` maps a public model name to provider, upstream id and USD per million input/output tokens. Values: `claude-opus-5-5` ($4/$20), `claude-sonnet-5-5` ($2/$10), `claude-haiku-5-5` ($0.10/$0.50 for prompts up to 100K tokens), `llama3.2:1b` ($0), with the source and date in a comment. Routing tiers (t06): cheap = `claude-haiku-5-5`, strong and default = `claude-opus-5-5`. Judge (t13): `claude-haiku-5-5`.
- **Reason:** prices and ids change; code must not.
- **Consequences:** the price table has one tier per model; Haiku prompts above 100K tokens (priced higher) are under-costed. `max_tokens` and input sizes in this project stay far below that; the limitation is stated in the README.

### D-09. Persistence: plain JDBC and Flyway
- **Decision:** Spring `JdbcClient` and hand-written SQL; Flyway migrations in `src/main/resources/db/migration`. Migration numbers are pre-assigned in WORK_PLAN.md; the reviewer commits each round's migrations as the first commit of the round ("round prep"), from the DDL in WORK_PLAN.md, and developers do not edit them.
- **Reason:** the important queries (`FOR UPDATE SKIP LOCKED`, `&&` on arrays, `<=>` on vectors, generated `tsvector`) are SQL-native; an ORM adds mapping without value. Pre-committed schema lets both developers code against the real tables in parallel.
- **Rejected:** JPA/Hibernate; Liquibase.
- **Consequences:** schema changes during a round go through the reviewer as a new migration number.

### D-10. JSON-schema validator
- **Decision:** `com.networknt:json-schema-validator` 1.5.9, draft 2020-12 by default (`$schema` honoured if present), schemas compiled once per request.
- **Reason:** Jackson 2 based, same `ObjectMapper` as Spring Boot 3, widely used, supports current drafts.
- **Rejected:** everit-json-schema (org.json, older drafts); networknt 2.x/3.x lines (newer major line, not needed on Jackson 2).

### D-11. Token estimation
- **Decision:** `TokenEstimator` with a character-ratio implementation: `ceil(chars / 3.0)` per message plus 4 tokens overhead per message; ratio configurable.
- **Reason:** used only where provider usage is not yet known (reservation, cancelled streams, chunk sizing); provider usage corrects the reservation at settlement, so the estimate affects only short-term admission, never the bill. Over-estimating is the safe direction.
- **Rejected:** jtokkit (an OpenAI tokenizer: precise for the wrong model); the provider's token-counting endpoint (a network call per request).
- **Consequences:** chunk sizes in tokens (t09) are approximate; the t13 report states the estimator.

### D-12. Test strategy
- **Decision:** JUnit 5 + AssertJ. Three levels:
  1. unit tests with fakes (`FakeLlmProvider`, in-memory implementations) for logic: chain order, RRF, chunker, backoff, cache key;
  2. integration tests on real Postgres (`pgvector/pgvector:0.8.7-pg16`) and Redis (`redis:7.4.12-alpine`) through Testcontainers singletons (`AbstractPostgresTest`, `AbstractRedisTest` without Spring; `AbstractIntegrationTest` with the full app on a random port);
  3. provider adapters and end-to-end HTTP tests against WireMock (`wiremock-standalone`), never a real provider.
  Awaitility for asynchronous outcomes; no fixed sleeps longer than the behaviour under test. One command: `./gradlew test`.
- **Reason:** behaviour that depends on Redis atomicity, SQL predicates or HTTP streaming must be tested against the real thing.
- **Consequences:** tests need Docker; no test needs network access beyond already-pulled images.

### D-13. Package layout
Base package `dev.raggateway`, by feature: `api` (HTTP edge, wire DTOs, client sinks, error rendering), `auth`, `core` (records, `RequestContext`, `TokenEstimator`), `pipeline`, `error`, `provider` (+ `provider.claude`, `provider.ollama`), `resilience` (fallback router, t03), `ratelimit`, `cost` (prices, usage endpoint, request log), `cache`, `routing`, `validation`, `metrics`, `rag` (+ `rag.ingest`, `rag.query`), `config`. Each feature owns its `@ConfigurationProperties` record. Filters are Spring beans ordered by `FilterOrder`, so adding a filter touches no wiring class.

### D-14. Changes to LLD contracts
| LLD | Now | Why |
|---|---|---|
| `ChatRequest(model, messages, maxTokens, double temperature, stream, jsonSchema)` | adds `Double temperature` (nullable), `topP`, `stop`, `includeUsage`; `withModel()` | absent temperature must not be cached; fields that change output belong in the cache key (t05) |
| `Tenant(id, tpm, budget, aclGroups)` | `Tenant(id, name, tpm, budget, allowedModels)`; groups on `ApiKeyPrincipal` | C16, t06 |
| `StreamSink.onComplete(Usage)` | `onComplete(Completion)` (finish reason, usage, model) | OpenAI final chunk, structural checks, model used |
| `RequestContext(requestId, apiKey, …)`, `providerUsed` | no API key; holds `caller`, list of `Attempt`s, cache status, route rule, outcome | auth at the edge; bill and log every attempt |
| `AuthFilter` in the chain | `ApiKeyAuthenticator` used by an edge interceptor on `/v1/**` | usage, ingestion and RAG endpoints need auth outside the pipeline; RAG reuses the caller |
| Controller with `SseEmitter` + executor | servlet response written on the request's virtual thread | C15, D-03 |
| `GatewayException` (checked) | unchecked, still declared on signatures | thrown from sinks and lambdas |
| Settlement in `ForwardingSink.onComplete` | `finally` after blocking `chain.next` | C23 |
| `FallbackRouter` with hand-written `CircuitBreaker` | Resilience4j; chain entries carry their own model; `supports()` picks the primary only | C6, D-07, C20 |
| Validation as a filter before the router | `ValidatingTerminal` decorating the router (t07) | C3, C8 |
| `RateLimiter.tryAcquire(tenantId, tokens, tpm)` | `tryAcquire(bucketKey, tokens, BucketSpec)` | C24 test control; per-key limits later without change |
| Lua `now_ms` argument | Redis `TIME` | C24 |
| `ResponseCache.get/put` | adds `tryLock/unlock`; `CachedResponse` adds finish reason, provider | stampede (t05), replay fidelity |
| Cache key `(tenantId, ChatRequest)` | tenant, resolved model, messages, temperature, topP, maxTokens, stop, jsonSchema; not `stream`/`includeUsage` | t05 rules |
| `Retriever.retrieve(collection, question, groups, topK)` | `retrieve(RetrievalQuery)` with tenant and mode | C17, t10 |
| `Embedder.embed(text)`, `embedBatch` | `embedDocuments(list)`, `embedQuery(text)`, `dimensions()` | task prefixes (G11) |
| `chunks(chunk_id = docId:ordinal, …, vector(1024))` | `tenant_id`, `title`, generated `chunk_id`, `vector(768)` | C1, C17 |
| `websearch_to_tsquery` | OR-semantics tsquery | C19 |
| Upsert hash over content + groups | content + title + sorted groups | t11 |
| (none) | `GatewayPipeline`, `RequestCompletionListener`, `ClientDisconnectedException`, `ModelSelector`, `OutputValidator`, `BudgetLedger`, `TokenEstimator` | contracts the tasks need |

### D-15. Request id and headers
- **Decision:** the edge generates a random UUID per request, returns it in `X-Request-Id`, puts it in the logging MDC (`requestId`, on every log line) and in the request-log row. An incoming `X-Request-Id` is ignored. Response headers: `X-Gateway-Provider`, `X-Gateway-Model`, `X-Gateway-Cache`, `X-Gateway-Route-Rule`; request header `X-Gateway-Cache-Bypass: true` skips the cache (`GatewayHeaders`).
- **Reason:** one id joins response, log row and log lines (G8); a client-supplied id could collide or be spoofed.

### D-16. OpenAI surface supported
- **Decision:** `POST /v1/chat/completions` accepts `model`, `messages` (string content, roles system/user/assistant; multiple system messages are joined), `max_tokens` / `max_completion_tokens`, `temperature`, `top_p`, `stop` (string or list), `stream`, `stream_options.include_usage`, `response_format` of type `text` or `json_schema`. Rejected with 400: `n` ≠ 1, `tools`/`functions`, non-string content (images), `response_format` `json_object`. Unknown fields are ignored. Responses carry `id` (`chatcmpl-` + request id), `object`, `created`, `model` (model actually used), `choices`, `usage`.
- **Reason:** covers what OpenAI SDKs send for text chat; everything else is out of scope (G22) and fails loudly instead of being silently dropped.

### D-17. Redis settings
- **Decision:** command timeout 50 ms for all Redis use (limiter, budget, cache); Lettuce `disconnectedBehavior = REJECT_COMMANDS` so a down Redis fails immediately instead of queueing; on any Redis error the limiter falls back to the per-instance in-memory bucket, the budget check is skipped, and the cache reads as a miss, each with a `gateway.ratelimit.failopen` or `gateway.cache.errors` count and a WARN log. Compose runs Redis with AOF (`everysec`), `maxmemory 128mb`, `maxmemory-policy volatile-lru`; budget keys have no TTL so they are never evicted; bucket and cache keys have TTLs.
- **Reason:** fail open for internal traffic (HLD deep dive 1) with a hard bound on added latency; budgets survive a Redis restart.
- **Consequences:** while Redis is down, N gateway instances admit up to N × the tenant limit and spend is not counted; losing Redis data resets the month's counter (recovery = sum from `request_log`, not built).
- **Amended in the t02 review:**
  - *Client-side timeout, server-side run.* A Lua script that exceeds the 50 ms client timeout has still been sent; if Redis is only slow, it runs later. The request is then charged twice (Redis bucket and in-memory bucket): over-limiting, never under-limiting, and it refills. The mirror case, a refund that times out and is also applied locally, over-credits by at most that refund, capped at capacity. Accepted; making scripts idempotent per request id would add a key per request for a sub-second edge case.
  - *No reconciliation across a switch.* The Redis and in-memory buckets are independent; a reservation taken on one side and refunded on the other moves at most one reservation per in-flight request. Accepted.
  - *Redis unreachable from start.* A refused connection fails at once (and `REJECT_COMMANDS` covers a dropped one), but a black-holed host costs up to the 500 ms connect timeout per Redis call. Not fixed now: t08 measures it (scenario 6b). If the added latency exceeds 50 ms per request, the fix is a short skip window (after a Redis failure, go straight to the fallback for ~1 s), recorded then.

### D-18. Embedder in this environment
- **Decision:** a deterministic fake embedder (768 dims, derived from a hash of the text, L2-normalised) is the default under the `test` profile and selectable for local runs without Ollama (`gateway.rag.embedder=fake`); the Ollama embedder is the default otherwise. Every number produced with the fake is labelled "fake embedder, not a model".
- **Reason:** Ollama is not reachable here; the pipeline, SQL and concurrency can still be tested for real.

### D-19. Metrics
- **Decision:** names fixed in `MetricNames` (each task records its own); labels only from `MetricNames.ALLOWED_LABELS`. Counters carry `tenant`; latency histograms carry `provider` and `model` only, with percentile histograms on (buckets for p50/p95/p99, AC-t04-7). Breaker state from `resilience4j-micrometer`.
- **Reason:** bounded cardinality (G13): ~50 tenants on counters is fine; tenant × model × provider × ~70 buckets on histograms is not needed to answer latency questions.
- **Rejected:** request id, key or raw model string as a label.

### D-20. Image pins
- **Decision:** `pgvector/pgvector:0.8.7-pg16`, `redis:7.4.12-alpine`, `prom/prometheus:v3.15.1`, `grafana/grafana:13.0.13`; the t08 mock provider uses `wiremock/wiremock:3.13.2`; k6 uses `grafana/k6` pinned by t08. Tests use the same Postgres and Redis tags (`TestImages`).
- **Reason:** `pgvector` 0.8.x has iterative index scans (t12); pinning stops silent changes.

### D-21. What cannot be verified in this environment, and how each is covered
| Not available here | Covered by | Owner step |
|---|---|---|
| Ollama (`llama3.2:1b`, `nomic-embed-text`) | Ollama adapters tested against WireMock with recorded NDJSON/JSON shapes; fake embedder for RAG | run the fallback and indexing with real Ollama |
| Real Anthropic API (no key) | Claude adapter tested against WireMock SSE bodies in the Messages format; no test calls the API | one live `curl` per the t01 notes |
| Laptop numbers | k6 + WireMock numbers labelled "cloud container, 4 vCPU / 15 GB" | rerun `bench` on the laptop, add rows |
| Real-embedding recall, answer scores | keyword-only recall is real here; vector/hybrid rows marked "fake embedder, not a model"; judge tested against WireMock | run the eval with real embeddings and a key |
| Public repo and push | nothing is pushed by agents | owner creates and pushes |
A developer who cannot verify something says so in the PR and stops; nothing is faked as verified.

### D-22. API-key storage
- **Decision:** keys are random 32-byte values (base64url, prefix `rgw_`), stored as SHA-256 hex; lookup is by hash (unique index). The plaintext is shown once by the seed script and never stored or logged.
- **Reason:** keys are high-entropy, so a fast unsalted hash resists brute force and allows an indexed lookup.
- **Rejected:** bcrypt/argon2 (designed for low-entropy passwords; would need a key-id prefix to find the row and costs ~50–100 ms per request).

### D-23. Key lookup cache
- **Decision:** Caffeine cache, key hash → principal, 30 s TTL, max 10,000 entries; unknown keys are not cached.
- **Reason:** no database round-trip per request (HLD "cached in memory").
- **Consequences:** revoking a key takes up to 30 s to apply on each instance.

### D-24. Eval harness and judge
- **Decision:** `src/eval/java` (own source set, `./gradlew eval`). Retrieval metrics are computed in-process against the indexed corpus. The answer judge calls Claude (`claude-haiku-5-5`) through its own SDK client, not through the gateway or its cache, with a structured verdict and a spend cap.
- **Reason:** eval code must not ship in the app; judge calls must not pollute gateway metrics or cache.

### D-25. Delivery process
- **Decision:** each round, two developers work in separate git worktrees on disjoint files against the committed contracts; the reviewer merges task branches to `main` strictly in task order, runs the integration step and the proof commands in WORK_PLAN.md. Rules for developers are in TEAM_RULES.md. Branches are named `tNN-a` and `tNN-b` and merged with `--no-ff` (amended in the t01 review to match the convention in use).

---

## Part 3: rulings from the t01 review (2026-11-16)

### D-26. Request mapping rules beyond D-16
- **Context:** the t01 mapper added rules D-16 did not state.
- **Decision:** accepted as the documented surface: `temperature` in [0, 2] and `top_p` in [0, 1] (OpenAI's ranges); at least one `user` or `assistant` message; `max_completion_tokens` wins over `max_tokens`; all `system` messages are joined with a blank line into one system message placed first.
- **Reason:** out-of-range sampling values would otherwise be cached or forwarded to Ollama unchecked; a system-only request is rejected by both providers anyway, so it fails early with a clear 400; `max_completion_tokens` is OpenAI's current name and `max_tokens` its deprecated alias; both providers take a single system prompt.
- **Rejected:** passing several system messages through (Claude has one `system` field); silently dropping out-of-range values.
- **Consequences:** a system message in the middle of a conversation loses its position; stated in the README limitations.

### D-27. Spring MVC's own errors and unknown paths
- **Decision:** errors raised by Spring MVC itself (405, 406, 415, unreadable body) keep their HTTP status and use the OpenAI error body (`invalid_request_error` type for 4xx). The auth interceptor covers `/v1/**`, so an unknown path under `/v1/` without a valid key is 401, and with a valid key 404.
- **Reason:** one error shape for every client-visible failure; the correct status is more useful than forcing 400; unauthenticated callers learn nothing about which routes exist.
- **Rejected:** mapping every MVC error to 400; authenticating only known routes.

### D-28. Which provider and model the response headers name
- **Context:** the client sink commits `X-Gateway-Provider`, `X-Gateway-Model` and the body `model` at the first byte, but the attempt was only recorded when it ended, so the sink fell back to the catalog entry for the requested model. With fallback (t03) that would name the primary while the fallback streams.
- **Decision:** contract change made in t01: `RequestContext.startAttempt(provider, model, fallback)` is called by every terminal immediately before each adapter call; the client sinks read `currentProvider()/currentModel()` when they commit. Retry and fallback happen only before the first byte, so the attempt announced at the first byte is always the one serving the response.
- **Rejected:** deferring headers to the end (impossible for a stream); HTTP trailers (clients ignore them).
- **Consequences:** the t03 router must call `startAttempt` before every attempt, including retries and fallbacks (added to the t03 plan).

### D-29. Claude API key header
- **Context:** SDK 3.3.0 sends a static credential given to `apiKey(..)` as `Authorization: Bearer` unless the request already carries `X-Api-Key` or `Authorization`.
- **Decision:** keep both: `apiKey(key)` (so the SDK never falls back to environment or profile credentials) and an explicit `X-Api-Key` default header (so the SDK skips Bearer). Exactly one credential header is sent.
- **Reason:** `X-Api-Key` is the Messages API's documented API-key header on every API version, works through proxies that only read it, and makes the WireMock assertion exact.
- **Rejected:** relying on the SDK default (works against the API today, but depends on SDK-internal header logic and on proxies forwarding Bearer).
- **Consequences:** re-check the header on every SDK upgrade (`claudeReceivesTheApiKeyHeaderAndNoSamplingParameters` covers it).

### D-30. Edge statuses
- **499 when the client cancels before the first byte:** accepted. Nothing was sent; 499 ("client closed request") keeps these rows apart from real 4xx in the log. After the first byte the row records 200.
- **Upstream 401/403:** changed in the review from 400 `upstream_rejected` to 502 `provider_error`: it means the gateway's own credential is missing or lacks access, which the caller cannot fix. In t03 such an error is not retried but does move to the next provider in the chain (the primary is unusable, not the request). Amended in the t03 review (D-37): with a fallback chain, a request whose chain is exhausted gets 503 `all_providers_failed` whatever the last failure was; 502 remains for failures that stop the chain (mid-stream, adapter bug).
- **Catalog model whose provider has no adapter:** 400 in t01 only (Ollama has no adapter until t03). From t03 the application fails at startup if any catalog model names a provider without an adapter, so a running gateway cannot reach this case.
- **Blank Claude API key:** the app starts with a placeholder (needed for WireMock and local runs without a key) and logs a WARN at startup; Claude calls then fail with 502 `provider_error`.

### D-31. Request latency end point
- **Context:** `latency_ms` was measured when the log writer ran, so it would grow with every listener that runs before it.
- **Decision:** `RequestContext.finish(...)` freezes `finishNanos` after the response is written and closed; the log writer and the t04 metrics listener both use it.
- **Consequences:** latency excludes the log insert and listener time, as C7 intends.

### D-32. Environment variable names
- **Context:** `application.yml` read `ANTHROPIC_BASE_URL` and `ANTHROPIC_API_KEY`. Other tools read the same names, and `ANTHROPIC_BASE_URL` was found set in this build environment by such a tool, so `bootRun` would have sent gateway traffic to that host silently.
- **Decision:** the gateway reads `GATEWAY_CLAUDE_BASE_URL`, `GATEWAY_CLAUDE_API_KEY` and `GATEWAY_OLLAMA_BASE_URL` (defaults: the public Anthropic API, empty key, `http://localhost:11434`). `.env.example` and `scripts/check_setup.sh` use the new names.
- **Rejected:** keeping the shared names (silent misrouting; a key meant for another tool used by the gateway).
- **Consequences:** the t14 secrets scan also looks for `GATEWAY_CLAUDE_API_KEY` followed by a key-like value.

### D-33. t02 settlement and usage details
- **Context:** after t01, attempts carry their own (possibly estimated) usage and model, and every request that reaches the pipeline gets a log row; the t02 plan did not yet say how settlement reads them.
- **Decision:**
  - Settlement in the rate-limit filter's `finally` uses `RequestContext.billedUsage()` and prices each attempt by its own model. Refund `reserved − billed` when positive; never take extra tokens when billed exceeds the reservation.
  - The budget receives the same cost the request-log row records.
  - Refund and budget-add failures are counted and logged, not retried, and never fail the request. When Redis fails during a refund, the in-memory bucket is refunded instead.
  - Requests rejected by the filter (429, 400 too large) are logged with zero tokens.
  - `GET /v1/usage?from=YYYY-MM-DD&to=YYYY-MM-DD` (UTC, `to` exclusive, default current month, at most 366 days) returns request count, token sums and cost for the caller's tenant only.
- **Reason:** one source of truth for what a request cost (log row = budget increment); output tokens are capped by `max_tokens`, so the only possible overshoot comes from the input estimate, and taking more tokens after the fact would let the bucket go negative for an error the tenant did not make.
- **Rejected:** debiting the bucket for the under-estimate (punishes the tenant for our estimator); settling from the last attempt only (failed validation attempts would be free, contradicting C8).
- **Consequences:** a 429 counts as a request in `/v1/usage`; 429 correctness in t08 is computed from these rows.

### D-34. Billing a provider failure before the first token
- **Context:** AC-t02-5 says a failed provider call gets its full reservation back. D-33 bills every attempt's usage, and the t01 terminal recorded an estimated input for every failure, so a provider 500 still cost the tenant its input estimate.
- **Decision:** an attempt that ends with an HTTP error status before any token (`FAILED_BEFORE_FIRST_TOKEN`, kind `HTTP_STATUS`) is recorded with zero usage, not estimated, so the whole reservation is refunded and the budget is not charged. A timeout or broken connection before the first token keeps the estimated input (flagged `usage_estimated`), because the provider may already have accepted and processed the prompt. Failures after the first token keep input estimate plus streamed text.
- **Reason:** providers do not bill error responses; charging the tenant for them punishes the tenant for a provider fault. Where the gateway cannot know, it errs towards charging the input, which is what the provider most likely bills.
- **Rejected:** estimated input for every failure (contradicts AC-t02-5); zero for every pre-token failure (under-counts real spend on timeouts after the provider started work).
- **Consequences:** every terminal applies the same rule through `pipeline/AttemptUsage.of` (the t01 direct terminal is gone; the t03 router and the t07 validating terminal use it).

### D-35. Integration test bases
- **Context:** Spring applies a superclass's `@DynamicPropertySource` after a subclass's, so `AbstractIntegrationTest`'s shared-Redis registration silently won over a subclass that registered its own Redis.
- **Decision:** `AbstractAppTest` (full app, shared Postgres, **no** Redis registration) and `AbstractIntegrationTest extends AbstractAppTest` (adds the shared Redis). Tests that need their own Redis extend `AbstractAppTest` and register it. `application-test.yml` points Redis at an `.invalid` host, so a test that registers none fails loudly instead of reaching a Redis it did not mean to use.
- **Rejected:** documenting the trap only (it fails silently); a per-test opt-out flag (a static `@DynamicPropertySource` cannot see the test class).

### D-36. t02 review rulings
- **Wiring change outside the owner's files:** Dev B's one-line change to `config/CoreConfig.java` (pass `CostCalculator` to `RequestLogWriter`) is accepted; D-33 requires it and the developer reported it. Rule stands: say so in the branch notes when a change outside your files is unavoidable.
- **`gateway.rate-limit.refill-period`:** accepted. Default 1 minute (long-run rate = TPM); tests shorten it to make `Retry-After` about a second without changing capacity (C24). Production config must keep 1 minute.
- **Too-large check before the budget check:** accepted. A request that can never fit is a 400 whatever the budget, and the check costs no Redis call.
- **`spent >= budget` rejects, so a budget of 0 blocks every request:** accepted; a 0 budget means "suspended tenant". Documented in the README limitations.
- **Unknown model priced 0 with an ERROR log:** accepted. It cannot happen while model selection admits only catalog models and `PriceTable` requires prices for every catalog model at startup; failing settlement or the log row instead would lose more information than it protects.
- **Fail-open counter:** all three call sites now register `gateway.ratelimit.failopen` through `FailOpenCounter.of(registry, component)`, with one description and the components `limiter` and `budget`.

### D-37. t03 review rulings
- **a. Exhausted chain:** always 503 `all_providers_failed`, even when the last failure was a 401/403/404. The client cannot fix any of them and "no provider could serve this now" is the accurate meaning; the error message lists each provider's failure, and the log row records the provider and error kind of every attempt. 502 `provider_error` stays for failures that stop the chain: a failure after the first token and an adapter bug. D-30's 502 applied to the single-provider t01 terminal.
- **b. Provider 404:** a configuration fault, like 401/403 (`ProviderException.configurationFault()`): the model is not there (not pulled into Ollama, or a retired Claude model id). Not retried, ignored by the breaker, and the router moves on (`fallbackAllowed()`); if the chain ends there the client gets 503. The earlier "Ollama 404 gets no fallback" (D-07 amendment) is superseded: the caller is not at fault, and another provider can answer. It never becomes the caller's 400.
- **c. Claude before the response headers:** the SDK's blocking call cannot be cancelled until headers arrive. The review replaced the adapter's `executeAsync` (OkHttp's dispatcher, which the SDK caps at 64 concurrent calls with no setting to raise it, so calls beyond 64 would queue and spend their first-token budget in the queue) with the SDK's synchronous call on its own virtual thread. At the first-token deadline the adapter stops waiting and moves on; the abandoned call parks one virtual thread and one socket until its headers arrive (then it is closed) or the read-timeout backstop (first-token + idle, 35 s) fires. Accepted: a black-holed Claude opens the breaker after 20 calls, which stops new abandoned calls. t08 measures it: scenario 7 (open streams) adds a run with the mock delaying response headers past the first-token timeout, reporting parked threads and heap at N concurrent requests.
- **d. Stale text:** `ClaudeClientConfig` javadoc and its startup WARN updated (read timeout is the adapter's backstop; an empty key now leads to a 401 that moves on, or 503).
- **e. Dev B's changes outside its file list:** accepted. `CoreConfig` lost the t01 terminal (required by the task); `AbstractAppTest` resets every circuit breaker before each test (breakers are singletons in a shared application context, so without it one test's failures open the breaker for the next); t01/t02 tests migrated to the router (e.g. AC-t01-10 now sees 503: its acceptance text allows "502 or 503"). Each was reported in the branch notes.
- **f. Ollama details:** an in-stream `{"error":...}` line is `HTTP_STATUS` 500, retryable before the first token and billed zero there (D-34), the same treatment as Claude's in-stream error event; Ollama is priced 0, so only the token bucket is affected. Missing `prompt_eval_count`/`eval_count` read as 0: Ollama omits zero values (a fully cached prompt). The body is now drained after `done:true` so the JDK client can reuse the connection (bounded by the idle watchdog; a drain failure is ignored).
- **g. Retry sleep:** confirmed: no `RetryImpl.setSleepFunction` (a JVM-wide static that would leak between tests and into production). Tests keep waits short through the backoff properties (base/cap), and the backoff itself is unit-tested (AC-t03-9).

### D-38. Request metrics details (t04 prep)
- **Context:** D-19 fixed names and the label allow-list. The request-level listener still needed rules for label values, buckets and end times that keep cardinality bounded and agree with the log row.
- **Decision:**
  - Label values come from bounded sets only. `model` is a catalog name or `other` (never the raw requested string, which a client controls); `provider` is the serving provider, `cache` or `none`; `status_class` is `2xx`/`4xx`/`5xx`; `code` is an `ErrorCode` or `none`. Constants are in `MetricNames`.
  - Latency histograms use explicit buckets (12 for total and TTFT, 10 for overhead) instead of Micrometer's default percentile histogram, and no client-side percentiles.
  - Durations end at `ctx.finishNanos()` (D-31). Tokens are counted per attempt under the attempt's model, cost from `CostCalculator`, so the metrics equal what is billed and logged.
  - Fixed-label counters start at 0.
  - Metrics already recorded by t01–t03, including Resilience4j's breaker series, are not duplicated.
- **Reason:** a label value taken from the request is how a metrics backend falls over: one bad client sends a new model string per request. Explicit buckets put boundaries where decisions are made (the 5 s first-token timeout, the 50 ms overhead target), give ~12 series per provider/model instead of ~70, and stay aggregatable across instances, which client-side percentiles (summaries) are not. Zero-initialised counters make `rate()` and "absent means healthy" panels correct before the first failure.
- **Rejected:** Micrometer's default percentile histogram (more series, boundaries not aligned with our thresholds); summaries with client-side p99 (cannot be summed over instances); `tenant` on histograms (×50 series for a question per-tenant counters already answer).
- **Consequences:** p99 from buckets is an interpolated estimate within a bucket (e.g. between 5 s and 10 s); the README states the bucket edges next to every percentile. The cache panel arrives with t05.

