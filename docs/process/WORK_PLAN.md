# Work plan: t01–t14

How the build is split between two developers (**Dev A**, **Dev B**) and a reviewer. Read with `docs/process/DECISIONS.md` (the "why") and `docs/process/TEAM_RULES.md` (the "how"). This file stands alone: every acceptance criterion is cited by id and its text restated. "Container" means this build environment; "owner" means a step only the project owner can run on her laptop or with her accounts.

## How a round works

1. **Round prep (reviewer):** on `main`, commit what both developers code against: the round's migrations (DDL below) and any internal interfaces listed for the round. Developers do not edit these files; schema changes go back to the reviewer as a new migration number.
2. **Build (developers, in parallel):** each developer creates a worktree from `main` after round prep (`git worktree add ../rg-tNN-a -b tNN-a`, or `-b tNN-b`), edits only the files listed as theirs, and keeps their branch green on its own (`./gradlew test`).
3. **Merge order:** Dev A's branch merges first. Dev B then rebases onto `main` and adds the cross-cutting end-to-end test named in the task ("B's final commit"), then merges. In a two-task round, the lower task merges completely (both its commits series) before the higher one.
4. **Integration step (reviewer):** run the proof commands, check each acceptance criterion as pass / owner-only, finalise the private task notes (not committed).

**Ownership rule:** a file belongs to exactly one developer per round. "Owns" includes creating and editing. A file not listed is not yours; ask the reviewer. Config: each feature has its own `@ConfigurationProperties` record with defaults in code; `application.yml` and `application-test.yml` have one owner per round, named in the task.

**Private task notes (not committed):** every task produces one; the drafting developer is named per task and the reviewer finalises it.

## Round plan

| Round | Task(s) | Dev A | Dev B |
|---|---|---|---|
| R1 | t01 | HTTP edge: OpenAI DTOs, mapper, controller, SSE/JSON sinks, errors, auth interceptor, request id, explicit model selection | Claude adapter (SDK), pipeline runner, direct terminal, key store, request log, token estimator, seed |
| R2 | t02 | Redis Lua token bucket, in-memory bucket, fail-open composite, Redis client settings | Rate-limit filter (reserve/settle), budget ledger, price table and cost, `GET /v1/usage` |
| R3 | t03 | Timeouts (first-token/idle watchdog) in both adapters, Ollama adapter | Fallback router with Resilience4j breaker + retry, full-jitter backoff, fallback chain |
| R4 | t04 | Request metrics listener, histograms, label-policy test | Prometheus/Grafana provisioning, dashboard JSON, dashboard and target checks |
| R5 | t05 | Redis cache store with lock, canonical cache key | Cache filter: eligibility, replay, single-flight wait, no-cache rules |
| R6 (two tasks) | t06 and t07 | t06: rule-based model selection | t07: JSON-schema validation, validating terminal, structural checks |
| R7 | t08 | Benchmark harness: mock providers, k6 scenarios, run-all, summary | README, Mermaid diagram, number checks, Gate 1 demo script |
| R8 | t09 | Document API (PUT/GET), document + outbox stores, size and admin checks | Chunker, embedders (fake, Ollama), indexing worker, chunk store, corpus scripts |
| R9 | t10 | Keyword and vector legs, hybrid retriever, RRF | RAG service, prompt, endpoint, citations |
| R10 (two tasks) | t11 and t12 | t11: incremental indexing, leases, dead jobs, delete | t12: ACL filtering hardening, iterative scan, leakage suite |
| R11 | t13 | Eval set, evidence check, retrieval metrics, report | Answer judge, spend cap, deterministic checks |
| R12 | t14 | Final README and number checks | Changelog, release checklist, secrets scan, decision-log check |

Two-task rounds: **R6** (t06 touches only model selection; t07 touches only the terminal and request validation; the contracts `ModelSelector` and `Terminal` separate them) and **R10** (t11 touches only ingestion and the worker; t12 touches only retrieval SQL and tests; schema and the `Retriever` contract are already on `main`). One commit series per task; t06 merges before t07, t11 before t12.

Private task notes drafted by: t01 A, t02 B, t03 A, t04 B, t05 A, t06 A, t07 B, t08 B, t09 A, t10 B, t11 A, t12 B, t13 A, t14 B.

## Pre-assigned migrations

| Version | Content | Round prep for |
|---|---|---|
| V1 | `CREATE EXTENSION vector` | scaffold (committed) |
| V2 | tenants, api_keys | t01 |
| V3 | request_log | t01 |
| V4 | documents, chunks, outbox | t09 |
| V5 | reserved for t11 (only if a new index is needed) | t11 |
| V6 | reserved for t12 (only if needed) | t12 |

---

## t01: Gateway core, OpenAI-compatible chat endpoint (Round R1)

**Design.** `POST /v1/chat/completions` runs on the request's virtual thread (D-03). The edge authenticates (`ApiKeyAuthenticator`, D-22/D-23), maps the OpenAI body to `ChatRequest` (D-16), creates a `RequestContext` with a fresh request id (D-15) and calls `GatewayPipeline.run` with a client sink. The client sink commits status and headers at the first byte, so errors before it are HTTP statuses and errors after it are one SSE error event (C15); a failed write throws `ClientDisconnectedException`, which the Claude adapter turns into closing the SDK `StreamResponse` (C23). In t01 the pipeline has two filters (`REQUEST_VALIDATION`, `MODEL_SELECTION` with explicit models only) and a `DirectProviderTerminal` that calls the one provider serving the model. The request-log row is written by a completion listener after the response is closed (C7). LLD sections: "Core models and the provider interface", "The request pipeline". Decisions: C3, C7, C15, C16, C23, D-03–D-06, D-14–D-16, D-22, D-23.

**Round prep (reviewer):** `V2__tenants_and_api_keys.sql`, `V3__request_log.sql` as below; `src/test/java/dev/raggateway/support/TestData.java` with `insertTenant(jdbc, id, tpm, budgetUsd, allowedModels)` and `insertKey(jdbc, tenantId, plaintextKey, groups, admin)` (hashes with SHA-256, returns key id).

```sql
-- V2
CREATE TABLE tenants (
  id                 text PRIMARY KEY,
  name               text NOT NULL,
  tokens_per_minute  bigint NOT NULL CHECK (tokens_per_minute > 0),
  monthly_budget_usd numeric(12,2) NOT NULL CHECK (monthly_budget_usd >= 0),
  allowed_models     text[] NOT NULL DEFAULT '{}',
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE api_keys (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  text NOT NULL REFERENCES tenants(id),
  key_hash   char(64) NOT NULL UNIQUE,          -- SHA-256 hex, never the key
  name       text NOT NULL,
  groups     text[] NOT NULL DEFAULT '{}',
  is_admin   boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz
);
-- V3: one row per request that reached the pipeline. No prompt or response text (G7).
CREATE TABLE request_log (
  request_id      uuid PRIMARY KEY,
  tenant_id       text NOT NULL,
  api_key_id      uuid,
  requested_model text NOT NULL,
  model_used      text,
  provider_used   text,
  route_rule      text,
  fallback_used   boolean NOT NULL DEFAULT false,
  attempts        smallint NOT NULL DEFAULT 0,
  input_tokens    integer NOT NULL DEFAULT 0,
  output_tokens   integer NOT NULL DEFAULT 0,
  usage_estimated boolean NOT NULL DEFAULT false,
  cost_usd        numeric(12,6) NOT NULL DEFAULT 0,
  latency_ms      integer NOT NULL,
  ttft_ms         integer,
  stream          boolean NOT NULL,
  cache_status    text NOT NULL,
  status          text NOT NULL,                -- ok | error | client_cancelled
  error_code      text,
  http_status     smallint NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX request_log_tenant_time ON request_log (tenant_id, created_at);
```

### Dev A: HTTP edge
- **Owns:** `api/ChatCompletionsController.java`, `api/dto/*` (OpenAI request, response, chunk records), `api/ChatRequestMapper.java`, `api/SseResponseSink.java`, `api/JsonResponseSink.java`, `api/ApiExceptionHandler.java`, `api/RequestIdFilter.java`, `api/WebConfig.java`, `auth/ApiKeyAuthInterceptor.java`, `auth/CallerArgumentResolver.java`, `pipeline/RequestValidationFilter.java`, `routing/ExplicitModelSelector.java`, `routing/ModelSelectionFilter.java`, `scripts/openai_client_check.py`, and their tests. Owns `application.yml` this round.
- **Codes against:** `GatewayPipeline`, `ApiKeyAuthenticator`, `ModelSelector`, `StreamSink`, `ErrorResponse`, `GatewayHeaders`, `GatewayProperties`. Until Dev B merges, tests use a `@TestConfiguration` with a `@Primary` stub `GatewayPipeline` and stub `ApiKeyAuthenticator`.
- **Must implement:** interceptor on `/v1/**` (Bearer key → principal, 401 `unauthorized` otherwise; never logs the header); request id in MDC and `X-Request-Id`; `X-Gateway-Cache-Bypass` copied to `ctx.setCacheBypassRequested`; mapper rules of D-16 and `max_tokens` default; `RequestValidationFilter` rejects `max_tokens` above `gateway.max-tokens-cap`; `ExplicitModelSelector`: model must be in `gateway.models`, `auto` → 400 "automatic routing is not enabled"; SSE format `data: {chat.completion.chunk}` lines, final chunk with `finish_reason`, then a usage chunk when `include_usage`, then `data: [DONE]`, then close the output stream; headers at first byte from `ctx` (`X-Gateway-Provider`/`Model` from the serving attempt, or `cache` and the request model when `cacheStatus == HIT`; `X-Gateway-Cache`; `X-Gateway-Route-Rule` when set); `ctx.markFirstTokenSent`; JSON sink buffers and writes one `chat.completion` with `Content-Length`.
- **Tests:** `ChatRequestMapperTest` (each D-16 rejection, defaults), `SseResponseSinkTest` (format; error before first token → status + JSON; after → SSE error event and no `[DONE]`; write failure → `ClientDisconnectedException`), `JsonResponseSinkTest`, `ChatEdgeIT` (stub pipeline: 401 cases, 400 cases, header and id behaviour).
- **Acceptance criteria:**
  - AC-t01-3: missing or unknown key → 401 JSON error, no provider call (WireMock request count = 0). *(edge part: stub pipeline never called; full check in B's final commit)*
  - AC-t01-4: malformed body or empty `messages` → 400 JSON error, no provider call.
  - AC-t01-12: the official OpenAI Java or Python client pointed at the gateway base URL completes one streaming call against the WireMock-backed gateway (scripted, in the repo; may be a documented manual check if adding the SDK to the build is heavy). *(script `scripts/openai_client_check.py`; run by the reviewer if `pip install openai` works here, else documented as a manual check)*

### Dev B: provider, pipeline, persistence
- **Owns:** `provider/claude/ClaudeProvider.java`, `provider/claude/ClaudeClientConfig.java`, `pipeline/DefaultGatewayPipeline.java`, `pipeline/DirectProviderTerminal.java`, `core/CharRatioTokenEstimator.java`, `config/CoreConfig.java` (beans for plain classes), `auth/JdbcApiKeyAuthenticator.java`, `auth/ApiKeyHasher.java`, `cost/RequestLogWriter.java`, `scripts/seed-demo.sh`, and their tests, plus `support/SlowSseServer.java` (raw-socket test server that streams slowly and records when the client closes).
- **Codes against:** `LlmProvider`, `ProviderException`, `Terminal`, `GatewayPipeline`, `RequestCompletionListener`, `RequestContext`, `Attempt`, `TokenEstimator`, `ApiKeyAuthenticator`, `GatewayProperties`, `MetricNames`.
- **Must implement:** Claude adapter per D-04/D-05/D-06 (SDK `AnthropicOkHttpClient.builder().baseUrl(..).apiKey(..).timeout(Timeout.builder().connect(..).read(..).build()).maxRetries(0)`; `client.messages().createStreaming(params)`; `MessageCreateParams.builder().model(String).maxTokens(long).system(String).addUserMessage/addAssistantMessage(String).stopSequences(list)`; never `temperature`/`top_p`; events via `RawMessageStreamEvent.messageStart()/contentBlockStart()/contentBlockDelta()/messageDelta()`, text via `delta().text()`; usage from `message_start` and the last `message_delta`; `AnthropicServiceException.statusCode()/headers()` mapped to `ProviderException` (429/5xx retryable, `retry-after` parsed); `AnthropicIoException` → `CONNECTION`); on `ClientDisconnectedException` close the `StreamResponse` and rethrow. Pipeline per `GatewayPipeline` javadoc. Direct terminal: one attempt, `Attempt` recorded with estimated usage on failure or cancel, non-retryable 4xx → `UPSTREAM_REJECTED`, other failures → `PROVIDER_ERROR`. Key store with Caffeine (D-23). Request log insert with timer and failure counter + WARN line (C7); `cost_usd` written as 0 until t02. Seed script: 2 tenants, 3 keys (one admin), keys printed once.
- **Tests:** `ClaudeProviderTest` (WireMock: multi-chunk stream, thinking block ignored, usage, stop-reason mapping, 400/429/500 mapping with `Retry-After`, request body has no `temperature`), `ClaudeProviderCancelTest` (with `SlowSseServer`: sink throws → upstream socket closed within 2 s), `DefaultGatewayPipelineTest` (filter exception → `onError`; disconnect → `CLIENT_CANCELLED`; listeners once; listener failure counted), `JdbcApiKeyAuthenticatorTest` (`AbstractPostgresTest`), `RequestLogWriterTest` (`AbstractPostgresTest`), `CharRatioTokenEstimatorTest`.
- **B's final commit (after rebasing on A):** `ChatCompletionsE2EIT` against WireMock Claude, covering AC-t01-1, -2, -3, -5, -6, -7, -8, -9, -10.
- **Acceptance criteria:**
  - AC-t01-1: with WireMock as Claude, a `stream:true` request returns `Content-Type: text/event-stream`, ≥ 2 content chunks in OpenAI chunk format, then `data: [DONE]`.
  - AC-t01-2: a `stream:false` request returns 200 JSON with `choices[0].message.content` and `usage`.
  - AC-t01-5: the first chunk reaches the client before the mock has sent its last chunk (mock delays between chunks; the test asserts arrival order). Proves no buffering.
  - AC-t01-6: after a completed request, exactly one request-log row exists with the request id from the response header, non-zero tokens and the right tenant.
  - AC-t01-7: no column of the request log contains the prompt text (test sends a unique marker string and searches the row).
  - AC-t01-8: the plaintext API key is in no table and no log line (test greps captured log output and queries the key table).
  - AC-t01-9: when the client closes the connection mid-stream, the upstream connection to WireMock is closed within 2 s, and one log row is written with a "client cancelled" status.
  - AC-t01-10: provider failure before the first token → HTTP 502 or 503 JSON; failure after the first token → an SSE error event and the stream ends; one log row either way.

### Integration step (reviewer)
Merge A, then B. Proof:
```bash
./gradlew clean test
docker compose config --services          # AC-t01-11: exactly postgres, redis
docker compose up -d && ./gradlew bootRun # in another shell: scripts/seed-demo.sh, then one curl with stream:true against a WireMock started with the t01 stub (documented in the private task notes)
```
- AC-t01-11: `docker compose config --services` lists exactly `postgres` and `redis` with no profile set. *(reviewer)*
- **Owner only:** one real call to Claude with her key (a `curl` in the private task notes) to confirm the adapter against the live API.

---

## t02: Token rate limiting, budget, cost attribution (Round R2)

**Design.** `RateLimitFilter` (order `RATE_LIMIT`) reserves `estimateInput + maxTokens` from the tenant bucket via `RateLimiter` (Redis + Lua with `TIME`, C5/C24), checks the month's spend via `BudgetLedger` (C22), and in `finally` refunds `reserved − billedUsage.total()` and adds the cost in micro-USD (C23). A request larger than capacity is 400 before touching Redis. Redis failure or a timeout over 50 ms falls back to a per-instance in-memory bucket (D-17). Cost comes from the price table in `gateway.models` (D-08); `GET /v1/usage` sums the caller's log rows. LLD: "Token-bucket rate limiter". Decisions: C5, C22, C23, C24, D-08, D-17.

**Round prep (reviewer):** no migration (the budget lives in Redis). Shared test fakes in `support/`: `FakeRateLimiter` (exact counts, no refill, records acquire/refund calls) and `InMemoryBudgetLedger`. Dev B's unit tests use these until Dev A's limiters are merged.

**Clarifications after the t01 review (D-33), both developers:**
- **Keys:** the filter passes the tenant id as the bucket key; `RedisTokenBucketLimiter` stores it under `rl:{<bucketKey>}`. The budget key is `budget:{<tenantId>}:<yyyy-MM>` (UTC month), integer micro-USD, no TTL (D-17).
- **Reservation:** `estimateInput(ctx.request()) + request.maxTokens()`, computed after model selection. Larger than the tenant's capacity → 400 `invalid_request` and metric reason `too_large`, without calling the limiter (AC-t02-8 path).
- **Order inside the filter:** budget check (429 `budget_exceeded`, no `Retry-After`), then `tryAcquire` (429 `rate_limited` via `RateLimitedException`, which already sets `Retry-After` in whole seconds ≥ 1), then `chain.next` in `try`, settlement in `finally`.
- **Settlement:** in `finally`, from `ctx.billedUsage()` (sum over every recorded attempt; failed attempts carry estimated usage) and `CostCalculator` over `ctx.attempts()` (each attempt priced by its own model). Refund `reserved − billed.total()` when positive. When billed exceeds reserved (input under-estimate) nothing extra is taken from the bucket: output can never exceed `max_tokens`, so the excess is bounded by the input estimate error. Add the cost to the budget only when it is > 0. A refund or budget add that fails is counted (`gateway.ratelimit.failopen{component=...}`) and logged, never retried, never fails the request.
- **Fail-open refund:** `FailOpenRateLimiter.refund` tries Redis and, if Redis fails, refunds the in-memory bucket.
- **Cost in the log:** `RequestLogWriter` writes `CostCalculator.cost(ctx.attempts())`, the same value the filter added to the budget, rounded to 6 decimals (`numeric(12,6)`). Latency stays at `ctx.finishNanos()` (D-31).
- **Rejected requests are logged:** a 429 or 400 thrown by the filter reaches the pipeline, so it gets one row (`status=error`, `error_code`, `attempts=0`, zero tokens). t08's 429-correctness number reads these rows.
- **`GET /v1/usage`:** query `from`/`to` as ISO-8601 dates (`YYYY-MM-DD`, UTC, `from` inclusive, `to` exclusive); default = current UTC month; `to` ≤ `from` or a range over 366 days → 400. Response: `{"tenant_id","from","to","requests","input_tokens","output_tokens","cost_usd"}`, `cost_usd` as a string with 6 decimals. `requests` counts every row, including rejected ones. Only the caller's tenant, from the authenticated key.
- **Tests and Redis timeouts:** `AbstractIntegrationTest` sets the Redis command timeout to 2 s; a test of the 50 ms fail-open path sets its own timeout and uses its own Redis container.

### Dev A: limiter implementations
- **Owns:** `ratelimit/RedisTokenBucketLimiter.java`, `src/main/resources/ratelimit/acquire.lua`, `src/main/resources/ratelimit/refund.lua`, `ratelimit/TokenBucket.java`, `ratelimit/InMemoryTokenBucketLimiter.java`, `ratelimit/FailOpenRateLimiter.java` (`@Primary RateLimiter`; counts `gateway.ratelimit.failopen{component=limiter}` and logs WARN once per 10 s), `config/RedisClientConfig.java` (Lettuce `ClientOptions.disconnectedBehavior(REJECT_COMMANDS)`), and tests.
- **Codes against:** `RateLimiter`, `BucketSpec`, `Decision`, `MetricNames`.
- **Tests:** `RedisTokenBucketLimiterTest` (`AbstractRedisTest`), `TokenBucketTest`, `FailOpenRateLimiterTest`, `RedisDownLimiterTest` (its own Redis container, stopped mid-test).
- **Acceptance criteria:**
  - AC-t02-1: tenant with 10,000 TPM; 100 parallel requests each reserving 1,000 tokens against real Redis → exactly 10 allowed, 90 get 429. *(limiter level: 10 allowed, 90 denied, with a slow-refill `BucketSpec`)*
  - AC-t02-2: the same test with two separate limiter instances (two connections, as two gateways would have) → still exactly 10 allowed.
  - AC-t02-3: every 429 for rate limit has `Retry-After` ≥ 1, and a retry after that many seconds succeeds. *(limiter level: `retryAfterMs` rounds up to ≥ 1 s and acquiring after the wait succeeds; HTTP level in B's final commit)*
  - AC-t02-7: with the Redis container stopped, requests still succeed, the local limiter still rejects above its limit, and the fail-open counter increases. *(limiter level; HTTP level in B's final commit)*

### Dev B: filter, budget, cost, usage
- **Owns:** `ratelimit/RateLimitFilter.java`, `ratelimit/RateLimitProperties.java`, `ratelimit/RedisBudgetLedger.java` (`INCRBY budget:{tenant}:{yyyy-MM}`, no TTL; fail-open counts `component=budget`), `cost/PriceTable.java`, `cost/CostCalculator.java`, `cost/UsageController.java`, `cost/UsageRepository.java`, edits `cost/RequestLogWriter.java` (write cost), owns `application.yml` this round, and tests.
- **Codes against:** `RateLimiter` (tests use `InMemory`-style fakes until A merges), `BudgetLedger`, `TokenEstimator`, `RequestContext.billedUsage()`, `RateLimitedException`, `ErrorCode.BUDGET_EXCEEDED`.
- **Tests:** `RateLimitFilterTest` (fake limiter and ledger; refund paths; 400 too-large path), `RedisBudgetLedgerTest` (`AbstractRedisTest`), `CostCalculatorTest`, `UsageControllerIT`, `BudgetOvershootIT` (20 parallel at the budget edge, fake terminal).
- **B's final commit (after rebasing on A):** `RateLimitE2EIT`: HTTP 429 with `Retry-After` and a successful retry (AC-t02-3), budget 429 with 0 WireMock calls (AC-t02-6), Redis stopped end to end (AC-t02-7) using its own Redis container and context.
- **Acceptance criteria:**
  - AC-t02-4: a request that reserves 1,000 and uses 300 leaves 700 back in the bucket (asserted by the next request's outcome).
  - AC-t02-5: a request whose provider call fails gets its full reservation refunded.
  - AC-t02-6: tenant over its monthly budget → 429 with error code `budget_exceeded` and no provider call.
  - AC-t02-8: a cache hit (once t05 exists; until then, a request rejected with 400) does not change the bucket.
  - AC-t02-9: `GET /v1/usage` for tenant A returns sums equal to `SUM()` over tenant A's log rows and nothing from tenant B.
  - AC-t02-10: cost in the log row equals `input × price_in + output × price_out` for the model used, to 6 decimal places.
  - AC-t02-11: the documented budget overshoot bound holds: with 20 parallel requests at the budget edge, final spend ≤ budget + 20 × max request cost.

### Integration step
Merge A, then B. `./gradlew clean test`; then with compose up and the app running, send requests above a seeded tenant's TPM and confirm `429` + `Retry-After`; `docker compose stop redis` and confirm requests still pass and `gateway_ratelimit_failopen_total` rises. The 429-correctness number is measured in t08.

---

## t03: Provider fallback, timeouts, retries, circuit breaker (Round R3)

**Design.** `FallbackRouter` replaces `DirectProviderTerminal`. For the selected model's provider, then each configured fallback entry `{provider, model}` (C20), it runs `Retry(CircuitBreaker(adapter.stream))` per D-07: retry only on retryable errors and only while no token reached the client, full-jitter backoff, upstream `Retry-After` honoured up to a cap (G9), open breaker → next provider at once. Each attempt is recorded in the context, the fallback flag set on non-primary attempts (C21). Timeouts live in the adapters: connect via the client, first token (to the first content event, D-06) and idle gap via a watchdog that closes the upstream response. All providers failed → 503. Decisions: C6, C20, C21, D-06, D-07. LLD: "Circuit breaker and fallback router" (behaviour kept, implementation Resilience4j).

**Round prep (reviewer, done):** no migration. Contract additions both developers code against: `pipeline/AttemptUsage` (the one billing rule for an attempt, D-34; the router must use it, not its own estimate), `ProviderException.fallbackAllowed()` (retryable failures plus upstream 401/403; any other 4xx is not), and the `Terminal` javadoc (call `ctx.startAttempt(provider, model, fallback)` before every adapter call; record every attempt). Test fixture `support/OllamaNdjson`, which builds `/api/chat` NDJSON bodies with shapes taken from the official Ollama API docs (Ollama is not reachable here). `provider/claude/ClaudeSse` is public and usable from the end-to-end tests.

**Ollama specifics (from the official docs, D-21):** request `{"model":"llama3.2:1b","messages":[...],"stream":true,"options":{"temperature","top_p","num_predict","stop"}}` (no `think` field); response `application/x-ndjson`, one object per line with `message.content` and `done:false`; the final object has `done:true`, `done_reason` (`stop` → `STOP`, `length` → `LENGTH`), `prompt_eval_count` (input tokens) and `eval_count` (output tokens). An error before the stream is an HTTP status with `{"error":"..."}` (404 model not pulled → not retryable, fallback not allowed: it is a configuration error); an `{"error":...}` line mid-stream is a failure after the first token. A stream ending without `done:true` is `MALFORMED_RESPONSE`. Model load on a cold laptop can take seconds: Ollama's first-token timeout is 30 s in config.

### Dev A: timeouts and the Ollama adapter
- **Owns:** `provider/StreamWatchdog.java`, `provider/ollama/OllamaProvider.java`, `provider/ollama/OllamaClientConfig.java`, edits `provider/claude/ClaudeProvider.java` (watchdog), and tests.
- **Codes against:** `LlmProvider`, `ProviderException.Kind`, `GatewayProperties.ProviderProperties`.
- **Must implement:** `provider/ProviderCatalogCheck.java`, a startup check that every model in `gateway.models` names a provider with an `LlmProvider` bean, failing startup with the offending model names (D-30). Ollama `/api/chat` with `stream: true` over JDK `HttpClient` (HTTP/1.1), model `llama3.2:1b`, `options.temperature`/`top_p`/`num_predict`/`stop` forwarded; NDJSON lines → tokens; `done_reason` → finish; `prompt_eval_count`/`eval_count` → usage; connection refused → `CONNECTION` retryable; watchdog for both adapters (`FIRST_TOKEN_TIMEOUT`, `IDLE_TIMEOUT`, retryable).
- **Tests:** `OllamaProviderTest` (WireMock), `StreamWatchdogTest`, `ClaudeProviderTimeoutTest` (WireMock fixed delay; headers then silence; long gap mid-stream).
- **Acceptance criteria:**
  - AC-t03-2: primary sends no first token within the configured time → fallback answers; total time < first-token timeout × attempts + backoff cap + 1 s. *(adapter part: the timeout fires within the configured time and is retryable; end to end in B's final commit)*

### Dev B: router, breaker, retry
- **Owns:** `resilience/FallbackRouter.java`, `resilience/ResilienceConfig.java` (registries, `TaggedCircuitBreakerMetrics`), `resilience/FullJitterBackoff.java`, `resilience/FallbackProperties.java` (`gateway.fallback`: chain, max attempts 2, base 200 ms, cap 2 s, retry-after cap 2 s, breaker window 20, threshold 50 %, open 30 s), deletes `pipeline/DirectProviderTerminal.java`, owns `application.yml` this round, and tests.
- **Codes against:** `Terminal`, `LlmProvider` (tests use `FakeLlmProvider`), `RequestContext.recordAttempt`, `MetricNames.PROVIDER_RETRIES/PROVIDER_FALLBACKS`.
- **Must implement (from the t01 review):** call `ctx.startAttempt(provider, model, fallback)` before every adapter call, including retries and fallbacks, so the response headers name the provider actually streaming (D-28); an upstream 401/403 is not retried but moves to the next provider (D-30). Record attempts with `AttemptUsage.of(...)` (D-34: an HTTP error status before the first token has zero usage; a timeout or connection failure before it has the estimated input). Move on to the next provider only when `ProviderException.fallbackAllowed()`; retry on the same provider only when `retryable()` and no token was sent. A 401/403 is ignored by the breaker (a configuration fault, not a health signal) and moves on at once.
- **Tests:** `FallbackRouterTest` (fakes; injectable `Clock` for the breaker), `FullJitterBackoffTest`.
- **B's final commit (after rebasing on A):** `FallbackE2EIT` with two WireMock servers (Claude SSE, Ollama NDJSON): AC-t03-1, -2, -3, -6, -7, -8, -10 over HTTP.
- **Acceptance criteria:**
  - AC-t03-1: primary returns 500 twice → response comes from the fallback; primary received exactly 2 calls.
  - AC-t03-3: primary fails after sending ≥ 1 token → no retry, no fallback, the client gets an SSE error; the content the client received contains no repeated text.
  - AC-t03-4: after the breaker opens, the next 10 requests cause 0 calls to the primary.
  - AC-t03-5: after the open period (clock controlled in the test), one trial call goes to the primary; success closes the breaker.
  - AC-t03-6: primary returns 400 → client gets 400; fallback received 0 calls.
  - AC-t03-7: both providers failing → 503 JSON and one log row with a failure status.
  - AC-t03-8: when the fallback answers, body `model` and the response header name the fallback model, and the log row's provider is the fallback.
  - AC-t03-9: backoff waits fall inside `[0, cap]` for each attempt (unit test over 1,000 samples).
  - AC-t03-10: breaker state per provider is visible on a metrics or actuator endpoint.

### Integration step
Merge A, then B. `./gradlew clean test`; `curl -s localhost:8080/actuator/prometheus | grep resilience4j_circuitbreaker_state`.
- **Owner only:** stop the primary (bad base URL or no network) with real Ollama running and see `llama3.2:1b` answer.

---

## t04: Prometheus metrics and Grafana dashboard (Round R4)

**Design.** A `RequestCompletionListener` records per-request metrics from the finished `RequestContext`: request counter, total and time-to-first-token histograms, gateway overhead (total time minus the sum of attempt durations), tokens and cost. Other counters were already recorded by their own tasks under the names in `MetricNames`. Histograms carry only `provider` and `model` (D-19). Prometheus and Grafana run under the `obs` profile with provisioning in `ops/`. Decisions: D-19, G13.

**Round prep (reviewer, done):** test fixture `support/PrometheusScrape` (parses `/actuator/prometheus`: values by label, label names, metric families; both developers use it) and fixed label values in `MetricNames` (`PROVIDER_NONE`, `PROVIDER_CACHE`, `MODEL_OTHER`, `CODE_NONE`). No migration.

**Rules from earlier rulings and this prep (D-38), both developers:**
- **Already exported, do not add again:** `gateway.ratelimit.rejections{tenant,reason}`, `gateway.ratelimit.failopen{component}` (t02, through `FailOpenCounter`), `gateway.provider.retries{provider}`, `gateway.provider.fallbacks{from_provider,to_provider}` (t03), `gateway.requestlog.write` and `.write.failures` (t01), `gateway.listener.failures{listener}`, and Resilience4j's `resilience4j_circuitbreaker_*{name,state,kind}` (t03). The listener adds only the request-level metrics below.
- **Label values are bounded:** `tenant` = tenant id; `provider` = the serving attempt's provider, `cache` for a hit, `none` when no provider was called; `model` = the catalog name actually used (the serving attempt's model, else `ctx.request().model()` **only if it is in `gateway.models`**, else `other`), never the raw requested string; `status_class` = `2xx|4xx|5xx` from `ctx.httpStatus()` (a 499 counts as `4xx`); `code` = `ctx.errorCode().code()` or `none`.
- **Durations end at `ctx.finishNanos()`** (D-31): total = finish − start; TTFT = first token − start, recorded only when a token was sent; overhead = total − sum of attempt durations (a cache hit's overhead is its total time).
- **Tokens and cost per attempt:** `gateway.tokens{tenant,model,direction}` adds each attempt's usage under that attempt's model (so the counters equal `billedUsage`, AC-t04-2); `gateway.cost.usd{tenant}` adds `CostCalculator.costUsd(ctx.attempts())`.
- **Histograms:** explicit buckets, not Micrometer's default percentile histogram (~70 buckets per series): `gateway.request.duration` and `gateway.request.ttft` 25, 50, 100, 250, 500 ms, 1, 2.5, 5, 10, 30, 60, 120 s; `gateway.overhead` 1, 2, 5, 10, 25, 50, 100, 250, 500 ms, 1 s. Tags `provider`, `model` (overhead: `provider` only). No client-side percentiles (summaries cannot be aggregated across instances).
- **Zero series at startup:** `MetricsConfig` registers the label-free and fixed-label counters (`failopen{component=limiter|budget}`, `requestlog.write.failures`) at 0, so dashboards and `rate()` see a series before the first failure. Series with a `tenant` label appear on first use.
- **Label-policy scope:** AC-t04-3 checks `gateway_*` and `rag_*` series (plus Prometheus `le`); Resilience4j's own labels (`name`, `state`, `kind`) are outside it.



### Dev A: request metrics
- **Owns:** `metrics/RequestMetricsListener.java`, `metrics/MetricsConfig.java` (percentile histograms and SLO buckets for the three timers), and tests.
- **Codes against:** `RequestCompletionListener`, `RequestContext`, `MetricNames`. Total and overhead durations end at `ctx.finishNanos()` (D-31), the same end point as the log row.
- **Tests:** `RequestMetricsIT`, `MetricLabelPolicyIT` (scrapes `/actuator/prometheus` after mixed traffic, parses label names of `gateway_*`, `rag_*` series, asserts they are in `MetricNames.ALLOWED_LABELS` plus Prometheus `le`/`quantile`).
- **Acceptance criteria:**
  - AC-t04-1: after 5 requests for tenant A, the request counter for A on `/actuator/prometheus` is 5.
  - AC-t04-2: token counters equal the usage totals of those 5 requests.
  - AC-t04-3: no metric has a label whose value is a request id, an API key or free text (test scans label names against an allow-list).
  - AC-t04-7: the histogram exposes buckets that allow p50, p95 and p99 to be computed.

### Dev B: Prometheus, Grafana, checks
- **Owns:** `ops/prometheus/prometheus.yml`, `ops/grafana/provisioning/**`, `ops/grafana/dashboards/rag-gateway.json`, `scripts/check_dashboard.py`, `scripts/check_obs.sh`, and `src/test/java/dev/raggateway/metrics/DashboardMetricsIT.java` (loads the dashboard JSON, drives a mix of traffic through WireMock (success, 429, provider 500 with fallback), scrapes with `PrometheusScrape`, asserts every metric named in a panel query exists: AC-t04-6 inside `./gradlew test`; the Python script does the same against a running app).
- **Codes against:** the names in `MetricNames` (Prometheus form: dots → underscores, counters `_total`, timers `_seconds_bucket/_count/_sum/_max`) and `resilience4j_circuitbreaker_state{name,state}`. Panels: request rate by tenant and status class; p50/p95/p99 of total, TTFT and overhead (`histogram_quantile` over `sum by (le, ...) (rate(..._bucket[5m]))`); tokens by tenant and model; cost by tenant; rejections by reason; breaker state; retries and fallbacks; fail-open; log-write failures. Only metrics that exist after t04; the cache panel is added in t05. Until t04-a is merged, `DashboardMetricsIT` is expected to fail for the request-level metrics: B's final commit (after rebasing on A) makes it green.
- **Acceptance criteria:**
  - AC-t04-4: `docker compose --profile obs config --services` lists 4 services; without the profile, 2.
  - AC-t04-5: with the obs profile up, the Prometheus targets API shows the app as `up`.
  - AC-t04-6: the dashboard JSON parses and every panel query names a metric that exists on the scrape page (scripted check).

### Integration step
Merge A, then B. `./gradlew clean test`; `docker compose --profile obs up -d`; app running; `bash scripts/check_obs.sh` (targets `up`); `python3 scripts/check_dashboard.py --scrape http://localhost:8080/actuator/prometheus`.
- **Owner only:** open Grafana on the laptop and take the dashboard screenshot for the README.

---

## t05: Exact-match response cache (Round R5)

**Design.** `CacheFilter` (order `CACHE`, before the limiter, C3) computes `SHA-256` over canonical JSON of tenant, resolved model, messages, temperature, top_p, max tokens, stop and schema (not `stream`, D-14). Only `temperature == 0` and no bypass header are eligible (D-05). A hit replays through the client sink with zero usage; a miss takes a short `SET NX PX` lock, calls `chain.next` with a buffering sink and stores the result on completion only if it ended with `stop`/`length`, came from the selected provider (C21) and was not invalid (C8). Waiters poll the cache until the lock TTL, then call through. Everything fails open. LLD: "Response cache". Decisions: C3, C21, D-05, D-17.

**Round prep (reviewer, done):** contracts `cache/CacheKeyStrategy` (key computed after model selection; what is in and out of the key; `cache:v1:` prefix), `cache/CacheProperties` (`gateway.cache`: enabled, ttl 6 h, lock-ttl 30 s since the t05 review, poll-interval 50 ms), and `ResponseCache` made exact: `tryLock` returns a lock token (empty when another request holds the lock, `FAILED_OPEN` on a Redis error) and `unlock(key, token)` releases only the caller's own lock. Test fake `support/InMemoryResponseCache` (TTL, exclusive locks, fail-open switch, call counts). No migration.

**Rules from earlier rulings (D-40), both developers:**
- **Where:** `CacheFilter` at `FilterOrder.CACHE`: after model selection (key holds the resolved model, AC-t06-8), before the rate limiter (a hit spends no tokens, never touches the bucket or the budget, AC-t05-10, AC-t02-8).
- **Eligible:** cache enabled, `temperature` exactly 0 (D-05: the caller's opt-in), and no `X-Gateway-Cache-Bypass: true` (`ctx.cacheBypassRequested()`, set by the edge since t01). Set `ctx.setCacheStatus(NOT_ELIGIBLE | BYPASS | HIT | MISS)` in every case; the client sinks already put it in `X-Gateway-Cache` and the log row's `cache_status`, and name the provider `cache` on a hit.
- **Hit:** `sink.onToken(content)` once, then `sink.onComplete(new Completion(finishReason, Usage.ZERO, model))`; no `chain.next`, no attempt recorded (cost 0, log row provider `cache`).
- **Store only when all hold:** the stream completed (`onComplete` reached the filter's wrapper), finish reason `stop` or `length` (not `content_filter`), `!ctx.fallbackUsed()` (C21), and the serving attempt `SUCCEEDED` with the selected model. Never on error, client disconnect, partial stream or invalid output (t07 never calls `onComplete` for those). Put **after** `super.onComplete(...)`, so the client's stream is closed first.
- **Single-flight:** on a miss, `tryLock(key, lockTtl)`. Holder (a token, including `FAILED_OPEN`): `chain.next` with the buffering wrapper, `unlock(key, token)` in `finally`. Not holder (empty): loop every `pollInterval`: `get` → hit (replay, status `HIT`); else `tryLock` → token (become the holder: the previous holder failed or finished without storing); give up after `lockTtl` and call through without a lock. Record the time spent waiting with `ctx.addWaitNanos(...)` so it is not gateway overhead (D-39).
- **Redis down:** get = miss, put ignored, `tryLock` = `FAILED_OPEN`; counted in `gateway.cache.errors{operation=get|put|lock|unlock}`; a request never waits on a dead Redis.
- **Metrics:** `gateway.cache.lookups{result=hit|miss|bypass|not_eligible}` (one per request through the filter) and `gateway.cache.errors{operation}`. Both are fixed-label counters: Dev B adds them to `MetricsConfig.registerZeroCounters` (D-38) and the cache panel (hit ratio, errors) to the dashboard; `DashboardMetricsIT` must stay green.



### Dev A: store and key
- **Owns:** `cache/RedisResponseCache.java` (JSON value, `SET key value PX ttl`; lock `SET lock:<key> <token> NX PX lockTtl` with a random token returned to the caller; `unlock(key, token)` deletes only if the value still equals the token, in one small Lua script, so an expired-and-retaken lock is never deleted by the old holder), `cache/CanonicalJsonCacheKeys.java` (implements `CacheKeyStrategy`), and tests.
- **Codes against:** `ResponseCache`, `CacheKeyStrategy`, `CacheProperties`, `CachedResponse`, `ChatRequest`, `MetricNames.CACHE_ERRORS`.
- **Tests:** `CacheKeysTest`, `RedisResponseCacheTest` (`AbstractRedisTest`; TTL; lock exclusivity; Redis down → miss and counter).
- **Acceptance criteria:**
  - AC-t05-3: changing any one of model, a message, temperature, max tokens or response format → a miss (one test per field). *(key level)*
  - AC-t05-8: the key's TTL in Redis equals the configured TTL (± 5 s).
  - AC-t05-11: semantically equal JSON with different key order or whitespace in the request body gives the same cache key.

### Dev B: filter
- **Owns:** `cache/CacheFilter.java`, edits `metrics/MetricsConfig.java` (zero counters only) and `ops/grafana/dashboards/rag-gateway.json` (cache panel), owns `application.yml` this round, and tests.
- **Codes against:** `ResponseCache` (use `support/InMemoryResponseCache` until A merges), `CacheKeyStrategy` (a trivial test lambda until A merges), `CacheProperties`, `GatewayFilter`, `RequestContext.fallbackUsed()/cacheBypassRequested()/addWaitNanos()`, `MetricNames.CACHE_LOOKUPS`.
- **Tests:** `CacheFilterTest` (fakes: eligibility, bypass, no-cache rules, single-flight with 50 threads).
- **B's final commit (after rebasing on A):** `CacheE2EIT` with WireMock and real Redis: AC-t05-1, -2, -5, -6, -7, -9, -10.
- **Acceptance criteria:**
  - AC-t05-1: two identical `temperature:0` requests → 1 provider call; the second has the hit header and identical content.
  - AC-t05-2: the same request from tenant B → a second provider call.
  - AC-t05-4: `temperature:0.7` twice → 2 provider calls.
  - AC-t05-5: a failed provider call leaves no key in Redis.
  - AC-t05-6: a response served by the fallback leaves no key in Redis.
  - AC-t05-7: 50 identical concurrent requests on a cold cache → exactly 1 provider call, 50 correct responses.
  - AC-t05-9: a hit with `stream:true` returns valid SSE ending in `[DONE]`; with `stream:false` returns JSON.
  - AC-t05-10: a hit leaves the tenant's token bucket unchanged and writes a log row with cost 0.
  - AC-t02-8 (carried over): a cache hit does not change the bucket.

### Integration step
Merge A, then B. `./gradlew clean test`; two identical `temperature:0` curls, second shows `X-Gateway-Cache: HIT`; `redis-cli TTL <key>`.

---

## t06 and t07 (Round R6, two tasks; t06 merges first)

**Round prep (reviewer, done):** `RequestContext.markLastAttemptInvalid(errorKind)` (t07 changes the last attempt's outcome to `INVALID_OUTPUT` while keeping its usage, so it is still billed and the cache never stores it). No migration, no new fixture: both tasks test with `FakeLlmProvider`, `RouterFixture`, WireMock and `ClaudeSse`.

**File split (no file is touched by both):** Dev A (t06): `routing/**`, `application.yml`. Dev B (t07): `validation/**`, `resilience/ResilienceConfig.java`, `pipeline/RequestValidationFilter.java`. Neither touches `CacheFilter`, `RateLimitFilter`, `FallbackRouter`, the client sinks or `MetricsConfig`. t06 merges first; Dev B rebases and adds the end-to-end test.

### t06: Model router (Dev A)

**Design.** `RuleBasedModelSelector` replaces `ExplicitModelSelector`. Rules come from `gateway.routing.rules` in order, first match wins; a rule matches on estimated input tokens, `max_tokens` and whether a schema is present, and names a catalog model. No match → `gateway.routing.default-model`. An explicit model is used as is if in the catalog and allowed for the tenant; otherwise 400 (unknown) or 403 `model_not_allowed`. Runs before the cache (C3). The selected model and rule go into the context, the header and the log row; a counter by rule. Tiers: cheap `claude-haiku-5-5`, strong/default `claude-opus-5-5` (D-08). Decisions: C3, D-08.
- **Owns:** `routing/RuleBasedModelSelector.java`, `routing/RoutingProperties.java`, edits `routing/ModelSelectionFilter.java`, deletes `routing/ExplicitModelSelector.java`, owns `application.yml` this round, tests `RuleBasedModelSelectorTest`, `ModelSelectionE2EIT`.
- **Codes against:** `ModelSelector`, `ModelSelection`, `Tenant.allows`, `MetricNames.ROUTING_DECISIONS`.
- **Rules (D-42):**
  - `gateway.routing`: `default-model` plus an ordered `rules` list. Each rule has a `name`, a `model` and a `when` block with optional `max-input-tokens` (estimate ≤), `min-input-tokens` (estimate ≥), `max-output-tokens` (`max_tokens` ≤) and `has-schema` (true/false); all given conditions must hold.
  - **`auto`:** the first rule that matches and whose model the tenant allows. Otherwise the default model if the tenant allows it, otherwise 403 `model_not_allowed`.
  - **Explicit model:** must be in the catalog (else 400 `invalid_request`) and allowed for the tenant (else 403 `model_not_allowed`). The rule is `null` for the header and log; the counter label is `explicit`.
  - **Startup:** every rule model and the default must be in `gateway.models`, else startup fails. Rule names are bounded (they come from config).
  - **Counter:** `gateway.routing.decisions{rule, model}` with `rule` = rule name, `default` or `explicit`.
  - **Defaults:** cheap = `claude-haiku-5-5`, strong/default = `claude-opus-5-5` (D-08). Starting rules: `short` (≤ 2,000 input tokens and `max_tokens` ≤ 1,024 and no schema → haiku), then the default. The rule tables in the private task notes say why.
  - The selected model feeds everything downstream unchanged: the cache key (AC-t06-8), the rate-limit reservation, the router's primary (its fallback chain is fixed config) and pricing.
- **Acceptance criteria:**
  - AC-t06-1: `auto` with a short prompt and small `max_tokens` → the cheap model is called (WireMock asserts the model in the upstream body).
  - AC-t06-2: `auto` with a prompt above the configured token threshold → the strong model.
  - AC-t06-3: an explicit model is passed through unchanged.
  - AC-t06-4: a model outside the tenant's allowed list → 4xx, 0 provider calls.
  - AC-t06-5: changing rule order in config changes the outcome with no code change (test loads two configs).
  - AC-t06-6: no rule matches → the configured default model.
  - AC-t06-7: the log row and response header carry the chosen model and the rule name.
  - AC-t06-8: two `auto` requests that resolve to different models never share a cache entry.
- Private task notes: Dev A.

### t07: Output validation, JSON schema (Dev B)

**Design.** `ValidatingTerminal` becomes the primary `Terminal`, delegating to `FallbackRouter`. With a schema it gives the router a buffering sink, validates the complete output with `OutputValidator` (networknt, D-10), retries the whole call once if invalid, and only then emits to the real sink (tokens as one burst, then completion) or 502 `invalid_model_output`; every attempt stays recorded and billed. A response with finish reason `length` is invalid when a schema was requested. Without a schema it passes through. Structural checks (finish reason and usage present; empty content with `stop` is malformed) run per attempt in a `StructuralCheckingProvider` decorator that the router wraps around each adapter, so a broken response before the first token is retried like any provider failure. `RequestValidationFilter` compiles the schema up front (400). Decisions: C3, C8, D-10.
- **Owns:** `validation/NetworkntOutputValidator.java`, `validation/ValidatingTerminal.java`, `validation/StructuralCheckingProvider.java`, edits `resilience/ResilienceConfig.java` (wrap adapters) and `pipeline/RequestValidationFilter.java` (schema check), tests `NetworkntOutputValidatorTest`, `ValidatingTerminalTest`, `StructuralCheckingProviderTest`. Does not touch `application.yml` (defaults in code).
- **Codes against:** `Terminal`, `OutputValidator`, `LlmProvider`, `RequestContext.markLastAttemptInvalid`, `MetricNames.VALIDATION_FAILURES`.
- **Rules (D-43):**
  - **Wiring:** change the router bean's return type to `FallbackRouter` so `ValidatingTerminal` can inject it, and mark `ValidatingTerminal` `@Primary` so the pipeline gets it. The router wraps every adapter in `StructuralCheckingProvider`.
  - **Schema requested (`ctx.request().hasSchema()`):**
    1. Give the router a buffering sink. Nothing reaches the client.
    2. On completion, invalid means any of: finish reason `length` (`TRUNCATED`), `content_filter` (`REFUSED`), or output that does not validate (`SCHEMA`).
    3. If invalid: `ctx.markLastAttemptInvalid(kind)`, count `gateway.validation.failures{model, kind=schema}`, and call the router once more.
    4. Still invalid: 502 `invalid_model_output`, with a summary of at most 3 errors and 300 characters that never echoes the output.
    5. Valid: emit the buffer as one `onToken`, then `onComplete`.
    - A provider error inside a buffered call is passed through as the router reported it (no extra retry).
  - **No schema:** pass the sink straight through. No buffering (AC-t07-5).
  - **Structural check per attempt (`StructuralCheckingProvider`):**
    - It swallows the adapter's `onComplete` and decides after `stream` returns.
    - Finish `stop` with no text before it → `ProviderException(MALFORMED_RESPONSE, retryable)`: retried or fallen back like any provider failure before the first token. Count `kind=structural`.
    - Otherwise forward `onComplete`.
    - Empty text with `length` or `content_filter` is a legitimate answer (C8 refinement).
    - Missing finish reason or usage is already `MALFORMED_RESPONSE` in both adapters.
  - **Schema compile:** `RequestValidationFilter` calls `OutputValidator.checkSchema` and returns 400 before the cache, the limiter or any provider call (AC-t07-6).
  - **Billing and cache:** every attempt stays recorded with its usage (`AttemptUsage`), including invalid ones (AC-t07-8). An invalid answer never reaches the cache's `onComplete`, and the serving attempt of a valid retry is the second one, so the cache's rules hold unchanged (AC-t07-7).
- **B's final commit (after rebasing on t06):** `ValidationE2EIT` with WireMock: AC-t07-1, -3, -4, -5, -7, -8.
- **Acceptance criteria:**
  - AC-t07-1: schema requested, valid JSON from the model → 200; the body validates against the schema.
  - AC-t07-2: invalid then valid → 200; the provider was called exactly 2 times.
  - AC-t07-3: invalid twice → 502 with error code `invalid_model_output`; exactly 2 provider calls.
  - AC-t07-4: schema requested with `stream:true` and invalid output → the client receives 0 content chunks.
  - AC-t07-5: no schema → tokens are streamed as in AC-t01-5 (no buffering regression).
  - AC-t07-6: malformed schema → 400, 0 provider calls.
  - AC-t07-7: after AC-t07-3, Redis holds no cache key for that request.
  - AC-t07-8: the log shows the tokens of both attempts as charged; the validation-failure counter increased by 2.
  - AC-t07-9: a truncated response (finish reason = length) with a schema → treated as invalid.
- Private task notes: Dev B.

### Integration step (R6)
Merge t06, then t07 (B rebases on `main` with t06). `./gradlew clean test`; curl with `"model":"auto"` shows `X-Gateway-Route-Rule`; curl with a `json_schema` response format against a WireMock stub that returns invalid JSON twice → 502 `invalid_model_output`.

---

## t08: README, architecture diagram, load-test numbers, Gate 1 (Round R7)

**Design.** A reproducible benchmark and a README whose every number comes from a run. Pieces:
- **Mock providers:** two WireMock containers act as Claude and Ollama, with fixed latency and streaming bodies.
- **The app:** the jar with `-Xmx512m` and virtual threads, as on the laptop.
- **Load:** k6 from the pinned `grafana/k6:2.3.0` image, run with `--network host`. A k6 binary can't be installed here, so the image is used everywhere.
- **One command:** runs every scenario and writes one committed summary file per run, `bench/summary/<run_id>.json`, in the shape of `bench/summary.schema.json`.
- **README check:** the measured table cites a run id and a key into that file for every number. A script fails the build step if a number has no matching value.
- **Labelling:** numbers measured here say "cloud container, 4 vCPU / 15 GB". The owner's laptop run adds rows labelled "8 GB MacBook Air". Nothing else is ever written as a measurement.

Decisions: C2, C7, C14, C27, D-20, D-21, D-39, D-45.

**Round prep (reviewer, done):**
- **Result-file contract:** `bench/summary.schema.json`, checked by `BenchSummarySchemaTest`. It has no numbers. Every required field is listed there; a scenario that was not run is absent, never zero.
- **Pinned images, already pulled here:** `grafana/k6:2.3.0` and `wiremock/wiremock:3.13.2`.
- No migration.

**File split:**
- **Dev A:** `bench/**` except `summary.schema.json`; `docker-compose.yml` (adds the `mock` profile); `src/main/resources/application-bench.yml`; one bucket entry in `metrics/MetricsConfig.java` (below).
- **Dev B:** `README.md`, `scripts/check_readme_numbers.py`, `scripts/check_mermaid.sh`, `scripts/demo.sh`, `scripts/quickstart_check.sh`, and their tests under `scripts/tests/`.

### Scenarios (all in one run; each writes its block of the summary)
| # | Scenario | Load model | What is reported (summary key) |
|---|---|---|---|
| 1 | **Overhead**: the same load straight to the mock and through the gateway; ≥ 10,000 requests through the gateway | open: `constant-arrival-rate` | `overhead`: "added latency seen by the client" = gateway − direct for TTFB (k6 `http_req_waiting`, which is time to first token here: the gateway commits headers with the first token, and the mock sends nothing before its first chunk) and for total time, p50/p95/p99; and, separately, "gateway processing overhead" = `gateway_overhead_seconds` p50/p95/p99 from the scrape (excludes provider time and waits, D-39). Never one number for both. |
| 2 | **Cache-hit latency**: a warmed set of repeated `temperature: 0` prompts | open | `cache_hit_latency` p50/p95/p99, requests. Target from the brief: p99 < 200 ms (reported, not asserted) |
| 3 | **Cache hit rate**: a pool of prompts with a stated repeat share (e.g. 50 %) | open | `cache_hit_rate`: configured share, measured rate, requests, workload text |
| 4 | **Cost per 1,000 requests**: one workload, four runs: baseline (temperature > 0, explicit strong model), cache only, routing only (`auto`), both | open | `cost_per_1k` from `SUM(cost_usd)` in `request_log` per run, labelled "modelled from list prices on a synthetic workload", with the price date |
| 5 | **Fallback**: stop the `mock-claude` container mid-run | open | `fallback`: kill time, success rate after it, added latency (after − before) p50/p95/p99 |
| 6 | **Rate limit**: one tenant driven above its TPM | open | `rate_limit` from `request_log` rows of that tenant: over-limit requests, those with 429 + `Retry-After` (k6 checks the header), share, admitted tokens/min vs limit |
| 6b | **Redis unavailable from start**: app started with Redis stopped (connection refused) and once with `REDIS_HOST` = a non-routable address (black-holed) | open | `redis_down_from_start`: added latency vs scenario 1 p50/p95/p99, errors. Decides the D-17 skip window |
| 7 | **Open streams**: N concurrent 5 s streams (N stepped, e.g. 500, 1,000, 2,000, 4,000) at `-Xmx512m`, app run with `-Djdk.tracePinnedThreads=short` | closed: `constant-vus` holding N | `open_streams`: largest N with 0 errors; per run errors, peak heap used, peak virtual threads (counted from a `jcmd Thread.dump_to_file` every 2 s: `jvm_threads_live` counts platform threads only), pinned-thread reports and their top frames (answers the D-03 question on OkHttp/SDK pinning) |
| 7b | **Headers delayed**: the Claude mock holds its response headers past the 5 s first-token timeout, at one N from 7 | closed | `open_streams.headers_delayed`: errors, fallback success rate, peak heap, peak virtual threads (abandoned calls, D-37 c) |
| 8 | **Log-insert cost** | from scenario 1 | `log_insert` p50/p99 from `gateway_requestlog_write_seconds` (Dev A adds 0.5, 1, 2, 5, 10, 25, 50, 100 ms buckets for this timer in `MetricsConfig`) |

### Dev A: harness, mocks, k6, result capture
- **Owns:** see the file split.
  - `bench/run-all.sh`.
  - `bench/k6/*.js`, one per scenario.
  - `bench/wiremock/{claude,ollama}/mappings/**`: Claude SSE in the Messages format (use the shapes of `ClaudeSse`); Ollama NDJSON (shapes of `OllamaNdjson`); fixed latency: first chunk after 300 ms, the rest over 1 s; scenario 7b's header-delay mapping.
  - `bench/seed-bench.sql`: bench tenants with large TPM and budget, one small-TPM tenant for scenario 6.
  - `bench/lib/*.py`: summary builder, scrape and SQL readers, schema check.
  - `docker-compose.yml`: profile `mock` with `mock-claude` and `mock-ollama` on fixed ports.
  - `application-bench.yml`: provider base URLs → the mocks; Redis and Postgres from compose.
  - The `MetricsConfig` bucket entry for `gateway.requestlog.write`.
  - The committed container run `bench/summary/<run_id>.json`.
- **`bench/run-all.sh --measured-on "<label>" [--only <scenario,...>]`:**
  - `--measured-on` is required; there is no default, so no run can be mislabelled.
  - Builds the jar, starts `docker compose --profile mock up -d`, starts the app with `-Xmx512m -Dspring.profiles.active=bench` (plus `-Djdk.tracePinnedThreads=short` for 7), and seeds.
  - Runs each scenario with `docker run --rm --network host -v "$PWD/bench:/bench" grafana/k6:2.3.0 run --summary-export /bench/results/<run_id>-<n>.json /bench/k6/<n>.js`. Raw output stays in the gitignored `bench/results/`.
  - Reads `/actuator/prometheus` and `request_log`, writes `bench/summary/<run_id>.json`, validates it against the schema, exits non-zero on any error, and stops what it started.
- **Rules:**
  - k6 thresholds may fail a run only on errors, not on latency: a slow number is still a number to report.
  - The overhead scenario's direct and gateway runs use the same arrival rate and mock.
  - Scenario 4 resets `request_log` between its four runs, or filters by run tag via a tenant per run. Prefer a tenant per run; no deletes.
  - Record the git commit and the `setup` block exactly as the schema asks, including `load_generator_shares_host: true` here.
- **Acceptance criteria:**
  - AC-t08-1: one documented command runs all scenarios from a clean checkout and exits 0.
  - AC-t08-5: the overhead scenario reports p50, p95, p99 and the request count, and the request count is ≥ 10,000.
  - AC-t08-6: the fallback scenario's summary shows the time the primary was killed and the success rate after it.
  - AC-t08-7: cost rows are labelled "modelled from list prices on a synthetic workload", with the price date.
  - AC-t08-10: the open-streams scenario reports N, heap setting and error count.
- **Private task notes:** Dev B drafts (below).

### Dev B: README, diagram, checks, Gate 1 demo
- **Owns:** see the file split. Codes against `bench/summary.schema.json` only (no dependency on Dev A's code). Tests for the scripts use fixture summaries under `scripts/tests/fixtures/` marked as test data (`measured_on: "test data, not a measurement"`).
- **README sections:**
  - What it is.
  - Quickstart in ≤ 4 commands.
  - Architecture (Mermaid `flowchart`: client → edge → filters → validating terminal → router → Claude/Ollama; Redis, Postgres, Prometheus).
  - Request path.
  - "Designed for": C2 and C27, assumptions, never measured.
  - "Measured", with columns Metric | Value | Unit | Measured on | Run | Source key.
  - Trade-offs.
  - Limitations: D-05 temperature, D-08 one price tier, D-17 fail-open, D-36 zero budget, D-38 bucket edges, D-43 buffered schema calls.
  - How to reproduce.
  - Every percentile row states the bucket edges or "k6 client-side".
- **`scripts/check_readme_numbers.py`:**
  - Every Value cell of the measured table must equal the summary value at Source key in `bench/summary/<Run>.json`. Rounding: latency to 1 decimal ms, rates to 1 decimal %, USD to 4 decimals.
  - Its Measured on must equal that file's `measured_on`.
  - "MacBook" may appear only in rows whose file says so.
  - A cell may read "pending laptop run" with an empty Run.
  - It also lists every other number in the README outside the designed-for table, for the reviewer's AC-t08-4 check.
  - Exit non-zero on any mismatch. B's final commit runs it on the committed container run.
- **`scripts/demo.sh` (Gate 1),** against the `mock` profile, in order:
  1. a 429 with `Retry-After`;
  2. a cache hit with `X-Gateway-Cache: HIT`;
  3. a fallback with `mock-claude` stopped (`X-Gateway-Provider: ollama`);
  4. a schema-validated JSON response;
  5. `GET /v1/usage`.
  - Each step prints what it expects and what it got, and fails if they differ.
- **`scripts/check_mermaid.sh`:** renders the README's Mermaid block with `minlag/mermaid-cli` (pin a tag at least two weeks old), or documents a rendered-once check if the image can't be pulled.
- **B's final commit (after rebasing on A):** fill the measured table from the committed container run and run the checks.
- **Acceptance criteria:**
  - AC-t08-2: every figure in the README benchmark table appears in a committed summary file produced by that command (a script compares them).
  - AC-t08-3: every table row has a non-empty "Measured on" value, and the string "MacBook" appears only in rows the owner added.
  - AC-t08-4: the README contains no latency, throughput or percentage figure outside the "designed for" table that is missing from the summary file (review check by the reviewer, listed in the review notes).
  - AC-t08-8: the Mermaid diagram block parses (scripted check or rendered once and noted).
  - AC-t08-9: following the quickstart on a clean checkout gives a 200 from a sample `curl` (run in the container against WireMock mode).
- **Private task notes:** Dev B drafts `t08`.

### Integration step
Merge A, then B. Proof:
```bash
./gradlew clean build
bash bench/run-all.sh --measured-on "cloud container, 4 vCPU / 15 GB"     # AC-t08-1; commit the new summary
python3 scripts/check_readme_numbers.py                                     # AC-t08-2, -3 (+ list for -4)
bash scripts/quickstart_check.sh && bash scripts/demo.sh && bash scripts/check_mermaid.sh
```
- **Runs here:** every scenario. Label "cloud container, 4 vCPU / 15 GB"; the README states that gateway, mocks and k6 share the host's CPUs.
- **Owner only:**
  - On the laptop, `bash bench/run-all.sh --measured-on "8 GB MacBook Air"`, commit the summary, and add the rows or column (the check accepts them).
  - The Grafana screenshot.
  - Laptop numbers decide the resume figures.

## t09: Document ingestion, chunking, embeddings into pgvector (Round R8)

**Design.** `PUT /v1/rag/collections/{collection}/documents/{docId}` (admin key, body ≤ 1 MB, `{"title", "content", "acl_groups"}`) writes the document row and an outbox row in one transaction and returns 202 (C4). A scheduled worker claims a few jobs (`FOR UPDATE SKIP LOCKED`, with a lease) and handles them one at a time: load the document, chunk it (headings → paragraphs → sentences, ~400/~50 estimated tokens, title stored on each chunk), embed in batches of at most 20 chunks per call with no transaction and no connection held, then replace the document's chunks and set its status in one transaction. Chunks copy `tenant_id`, `collection_id` and `acl_groups` (C16, C17) and have `vector(768)` (C1), a generated `tsvector`, HNSW cosine and GIN indexes. `GET` on the same URL (admin key) returns status and chunk count. The fake embedder is the default under the test profile (D-18). Heap and connections are bounded per D-47. LLD: "Indexing worker and the outbox". Decisions: C1, C4, C16, C17, D-18, D-46, D-47.

**Round prep (reviewer, on `main`):**
- `V4__rag_schema.sql`: `documents`, `chunks` (`vector(768)`, generated `tsv`, HNSW cosine, GIN on `tsv` and `acl_groups`, `(tenant_id, collection_id)`), `outbox` (lease, attempts, `last_error`), with CHECK constraints on statuses and non-empty groups; `documents.tenant_id` references `tenants`.
- Contracts in `rag/ingest/`: `DocumentUpsert` (validated PUT body and address; id and group patterns), `DocumentHash` (SHA-256 over title, content, sorted groups, length-prefixed), `DocumentStatus`, `IndexedDocument`, `UpsertResult`, `OutboxEvent`, `OutboxStore` (`insert`, `claimBatch(max, lease)`, `markDone`, `release`, `markDead`), `DocumentStore` (`upsert`, `find`, `findById`, `markFailed`), `ChunkStore` (`replaceForDocument`, which also sets status `indexed` and the count in its transaction; `countForDocument`). Existing: `Chunker`, `Embedder`, `ChunkText`.
- Test fixtures: `support/OllamaEmbedJson` (`/api/embed` bodies, from the official docs), `src/test/resources/rag/chunker-fixture.md` (three heading levels, a ~630-token paragraph, a list, a code block, abbreviations and version numbers), `rag/ingest/IngestContractsTest`.
- Corpus pin checked from here: `kubernetes/website` at `adaa04d017c75981660828f869b364a266b0ef53`; `LICENSE` there is CC BY 4.0; every `.md` under `content/en/docs/{concepts,tutorials,setup}` is 256 files, 2.92 MB, largest 65 KB, and all 256 returned 200 from `raw.githubusercontent.com` (D-47).

### Dev A: write API and stores
- **Owns:** `rag/ingest/DocumentController.java` (PUT, GET), `rag/ingest/DocumentService.java` (`@Transactional` upsert: document + outbox), `rag/ingest/JdbcDocumentStore.java`, `rag/ingest/JdbcOutboxStore.java` (claim SQL as in the LLD, lease and limit as parameters), `rag/ingest/DocumentWriteFilter.java` (PUT only: 403 for a non-admin key, 413 over the byte limit, 503 `overloaded` over the write cap, all before the body is read; D-47), `ErrorCode` additions if any, and tests.
- **Codes against:** `DocumentUpsert`, `DocumentStore`, `OutboxStore`, `ApiKeyPrincipal.admin()`, `ErrorCode.FORBIDDEN/NOT_FOUND/PAYLOAD_TOO_LARGE/OVERLOADED`.
- **Tests:** `DocumentControllerIT`, `DocumentServiceTxIT` (forced failure after the document write), `RagSchemaTest` (`AbstractPostgresTest`: column type, indexes, extension version), `JdbcOutboxStoreTest` (two concurrent claims never return the same job; an expired lease is claimable again), `DocumentWriteFilterTest`.
- **Acceptance criteria:**
  - AC-t09-2: PUT with a non-admin key → 403; no rows written.
  - AC-t09-3: the document row and the outbox row are atomic: a forced failure after the document write leaves neither.
  - AC-t09-5: the embedding column type is `vector(768)`; inserting a 1,024-length vector fails.
  - AC-t09-9: `\d chunks` shows the HNSW index, the GIN index on the tsvector and the GIN index on ACL groups.
  - AC-t09-10: `SELECT extversion FROM pg_extension WHERE extname='vector'` ≥ 0.8.0, and the compose file pins a 0.8.x image tag.
  - AC-t09-12: a document over the size limit → 413, by `Content-Length` and also for a chunked body without one; no rows written.
  - AC-t09-13: with `max-concurrent-writes` = 1 and one PUT held inside the transaction, a second PUT gets 503 `overloaded` with `Retry-After: 1`; after release, PUTs succeed again.
  - AC-t09-14: `GET` returns 404 for a document of another tenant (same collection and doc id), never 403; an invalid id or group → 400 naming the field.

### Dev B: chunking, embedding, worker, corpus
- **Owns:** `rag/ingest/HeadingParagraphSentenceChunker.java`, `rag/ingest/FakeEmbedder.java`, `rag/ingest/OllamaEmbedder.java`, `rag/ingest/IndexingWorker.java`, `rag/ingest/JdbcChunkStore.java`, `rag/RagProperties.java` (`gateway.rag`, D-47 defaults), `rag/RagConfig.java` (embedder choice, `@EnableScheduling`, worker conditional on `gateway.rag.worker.enabled`), `scripts/corpus/fetch_corpus.py`, `scripts/corpus/load_corpus.py`, `data/corpus-manifest.tsv` (path and groups per line, the pinned commit in a header comment), `data/CORPUS_ATTRIBUTION.md`, owns `application.yml` and `application-test.yml` this round (worker disabled under the test profile), and tests.
- **Codes against:** `Chunker`, `Embedder`, `ChunkText`, `OutboxStore`, `DocumentStore`, `ChunkStore`, `IndexedDocument`, `TokenEstimator`, `support/OllamaEmbedJson`, `rag/chunker-fixture.md`.
- **Tests:** `HeadingParagraphSentenceChunkerTest`, `FakeEmbedderTest`, `IndexingWorkerTest` (in-memory stores, counting fake embedder, worker driven by calling `pollOnce()`), `OllamaEmbedderTest` (WireMock), `JdbcChunkStoreTest` (`AbstractPostgresTest`), `scripts/tests/test_corpus.py` (front matter and shortcode stripping, path → doc id).
- **B's final commit (after rebasing on A):** `IngestionE2EIT` (the only test class that enables the scheduled worker): AC-t09-1, -4 and -15 through HTTP and the real worker.
- **Acceptance criteria:**
  - AC-t09-1: PUT with an admin key → 202; within 10 s the document's status is `indexed` and its chunk count is > 0 (fake embedder).
  - AC-t09-4: every chunk row has the tenant id, collection and ACL groups of its document.
  - AC-t09-6: chunker unit tests on the fixture: no chunk above the max size; no chunk ends mid-sentence ("e.g.", "i.e.", "Dr." and "1.2" are not sentence ends); consecutive chunks in a section overlap; the chunks cover the whole text; a heading starts a new chunk. A single sentence longer than the max is split on whitespace (separate test).
  - AC-t09-7: the worker calls the embedder once per batch of at most `embed-batch-size` chunks, not once per chunk (fake counts calls and batch sizes; a 45-chunk document → 3 calls).
  - AC-t09-8: the Ollama adapter posts to `/api/embed` with `model`, `input` as an array, `truncate: false`, and every input starting with `search_document: `; `embedQuery` uses `search_query: `; it rejects a response whose vector count differs from the input count or whose vector length ≠ 768; a non-2xx status with `{"error"}` becomes an embedding failure naming the status (WireMock asserts the bodies).
  - AC-t09-11: the fetch script downloads every manifest entry at the pinned commit (HTTP 200 for all), and the count is between 250 and 350. *(reviewer runs it here; `raw.githubusercontent.com` is reachable)*
  - AC-t09-15: the worker holds no database connection while the embedder runs: with the test pool of 3 and the fake embedder blocked inside a call, Hikari's active connections are 0 and three concurrent `GET`s succeed.
  - AC-t09-16: the fake embedder is deterministic, unit length, and ignores the task prefix; two texts sharing most words score a higher cosine than two unrelated texts (D-47).

### Integration step
Merge A, then B. `./gradlew clean build`; `python3 scripts/corpus/fetch_corpus.py && python3 scripts/corpus/load_corpus.py` against the app with the fake embedder; check `SELECT count(*), count(DISTINCT document_id) FROM chunks` and that every document is `indexed`. Indexing throughput here is labelled "fake embedder, not a model".
- **Owner only:** index the corpus with real `nomic-embed-text` on the laptop (`ollama pull nomic-embed-text`, `gateway.rag.embedder=ollama`), and note the indexing time per document there.

---

## t10: Hybrid search with RRF, and the RAG query endpoint (Round R9)

**Design.** The keyword leg uses OR semantics (C19) ranked by `ts_rank_cd` (C18); the vector leg orders by cosine distance on the query embedded with `embedQuery` (`search_query:` prefix). Both legs have `tenant_id`, `collection_id` and `acl_groups && :groups` in SQL from the first commit, so no commit serves chunks without the ACL filter; both take top 50 and run in parallel on virtual threads. RRF k = 60, 1-based ranks, ties by chunk id. `RagService` builds a prompt that keeps retrieved text inside a delimited sources block and instructs the model to answer only from it and cite ids, then runs a non-streaming `ChatRequest` (`model: auto`, `temperature: 0`) through `GatewayPipeline` with a collecting sink, so limits, cache, fallback, validation, log and metrics apply. Zero chunks → fixed "no answer" with no provider call. LLD: "RAG: hybrid retriever, RRF and the ACL-filtered SQL". Decisions: C17, C18, C19, D-14.

**Round prep:** none.

### Dev A: retrieval
- **Owns:** `rag/query/ChunkSearchRepository.java`, `rag/query/HybridRetriever.java`, `rag/query/Rrf.java`, and tests (incl. `OllamaEmbedderQueryPrefixTest`).
- **Codes against:** `Retriever`, `RetrievalQuery`, `RetrievedChunk`, `RetrievalMode`, `Embedder`, `MetricNames.RETRIEVAL_DURATION`.
- **Acceptance criteria:**
  - AC-t10-1: RRF unit test on fixed lists: an item at rank 1 in both lists scores 2/61; an item in one list at rank 1 scores 1/61; order matches a hand-computed expected list.
  - AC-t10-2: a question with one word that appears nowhere in the corpus still returns ≥ 1 keyword hit (C19).
  - AC-t10-3: a query for a unique token present in exactly one chunk (for example a flag name) returns that chunk in the top 5 in hybrid and keyword modes.
  - AC-t10-4: both SQL statements contain the tenant and collection predicates (integration test with two tenants holding the same doc ids; no cross-tenant result).
  - AC-t10-5: the two legs run concurrently (each leg delayed 300 ms in a test double; total < 500 ms).
  - AC-t10-9: the same query twice returns the same chunk order.
  - AC-t10-10: the retrieval mode switch changes which legs run (asserted by query counts).
  - AC-t10-11: the query embedding request carries the `search_query:` prefix.

### Dev B: RAG service and endpoint
- **Owns:** `rag/query/RagService.java`, `rag/query/RagController.java` (`POST /v1/rag/query`, `topK` default 5 max 20, optional `mode`), `rag/query/RagPromptBuilder.java`, `rag/query/CollectingSink.java`, `rag/query/RagAnswer.java`, and tests.
- **Codes against:** `Retriever` (fake until A merges), `GatewayPipeline`, `RequestContext`, `ApiKeyPrincipal.groups()`.
- **B's final commit (after rebasing on A):** `RagQueryE2EIT` with indexed documents (fake embedder) and WireMock Claude: AC-t10-6, -7, -8 on the real retriever.
- **Acceptance criteria:**
  - AC-t10-6: every citation refers to a chunk that was in the prompt sent upstream (WireMock captures the prompt).
  - AC-t10-7: empty retrieval → the "no answer" response and 0 provider calls.
  - AC-t10-8: a RAG query produces a request-log row and consumes the tenant's token bucket (proves pipeline reuse).

### Integration step
Merge A, then B. `./gradlew clean test`; with the corpus loaded, `curl /v1/rag/query` with an `eng` key.

---

## t11 and t12 (Round R10, two tasks; t11 merges first)

### t11: Incremental indexing (Dev A)

**Design.** The upsert hashes title, content and sorted ACL groups (D-14); same hash → 202 with `"status":"unchanged"`, no outbox row, no embedding. Changed → new job; the worker skips a job whose hash no longer matches the document (stale) before embedding, which saves the call; the last PUT already wins regardless of job order, because since t09 the chunk swap writes only the version the locked document row still holds (D-48). Claims use `FOR UPDATE SKIP LOCKED` with a 5-minute lease; expired leases are reclaimed; after 5 attempts a job is `DEAD` and counted. `DELETE` on the document URL (admin) removes the document; chunks and jobs cascade. Readers see old or new chunks, never a mix, because the swap is one transaction. Decisions: C4, D-14.
- **Owns:** edits `rag/ingest/DocumentService.java`, `rag/ingest/DocumentController.java`, `rag/ingest/JdbcOutboxStore.java`, `rag/ingest/JdbcDocumentStore.java`, `rag/ingest/IndexingWorker.java`; adds `rag/ingest/IngestMetrics.java`; tests. May request V5 from the reviewer for an index. Owns `application.yml` this round.
- **Acceptance criteria:**
  - AC-t11-1: PUT the same document twice → the embedder is called for the first only; the second returns 202 with an "unchanged" marker.
  - AC-t11-2: PUT with changed content → after indexing, no chunk of the old version remains.
  - AC-t11-3: PUT with the same content and different ACL groups → all chunks carry the new groups.
  - AC-t11-4: load 300 documents, change 10, load all 300 again → embedder called for exactly 10 documents.
  - AC-t11-5: two workers polling at once over 50 jobs → each job is processed exactly once.
  - AC-t11-6: a job whose worker "crashes" (lease expires) is processed by another worker.
  - AC-t11-7: a job that always fails is marked dead after 5 attempts and the dead-job metric is 1.
  - AC-t11-8: two quick PUTs of the same document (v1, v2) → final chunks are v2 regardless of job order.
  - AC-t11-9: during a re-index, a concurrent reader sees only all-old or all-new chunks (test with a paused transaction).
  - AC-t11-10: DELETE removes the document and all its chunks; a later query does not return them.
- Private task notes: Dev A.

### t12: Chunk-level ACL filtering (Dev B)

**Design.** The ACL predicate is already inside both SQL legs (t10). This task proves and hardens it: a key with no groups short-circuits to "no answer" with no SQL and no provider call; the vector leg enables pgvector iterative index scans (`SET LOCAL hnsw.iterative_scan = strict_order`, `hnsw.max_scan_tuples` from config) inside its transaction so a selective filter still fills the candidate list; a leakage suite checks three levels (retrieved chunk ids, the prompt sent upstream, the citations) and the cache (the RAG cache key contains the prompt, which contains only the caller's chunks). Decisions: C16, C17, D-14.
- **Owns:** edits `rag/query/ChunkSearchRepository.java`, `rag/query/RagService.java`; adds `rag/query/AclProperties.java` (`gateway.rag.acl`: iterative scan on/off, max scan tuples), tests `RagAclLeakageIT`, `FilteredHnswUnderfillIT`, `AclExplainIT`. Does not touch `application.yml` (defaults in code).
- **Acceptance criteria:**
  - AC-t12-1: a key with group `eng` queries a collection with `eng`, `sre` and `security` documents → every returned chunk's groups include `eng`.
  - AC-t12-2: for 10 questions whose only relevant documents are restricted, asked with a key lacking the group → 0 restricted chunk ids in retrieval, 0 restricted text in the upstream prompt (WireMock capture), 0 restricted citations.
  - AC-t12-3: tenant B with a group of the same name as tenant A's gets 0 of tenant A's chunks.
  - AC-t12-4: a key with an empty group set → the "no answer" response, 0 provider calls.
  - AC-t12-5: under-fill test: with the HNSW index forced (`enable_seqscan = off`) and a filter matching < 5% of ≥ 5,000 chunks, the vector leg returns the full requested candidate count with iterative scan on; the same query with it off returns fewer. The test records both counts.
  - AC-t12-6: `EXPLAIN` of both legs shows the ACL predicate inside the scan, and the test asserts the repository returns no row that was later dropped in Java (no post-filter code path exists).
  - AC-t12-7: after a PUT that removes a group from a document and the worker run, a key with only that group no longer retrieves it.
  - AC-t12-8: key A (group `security`) and key B (group `eng`) ask the same question; B's response contains no text from security-only chunks, including when A asked first (cache test).
- Private task notes: Dev B.

### Integration step (R10)
Merge t11, then t12 (B rebases on `main` with t11). `./gradlew clean test`; record the AC-t12-5 counts and the leakage count (must be 0) in the round notes.

---

## t13: Eval set, recall@k, answer scoring (Round R11)

**Design.** A versioned eval file over the pinned corpus; gold evidence is a document id plus a short passage that must appear verbatim in that document, so labels do not depend on chunk size (matching rule: a retrieved chunk is relevant if it belongs to the gold document and contains at least 60 % of the evidence passage's words in order, or the whole passage). One command indexes the corpus at 200, 400 and 800 target tokens into separate collections and reports recall@1/5/10 and MRR for keyword, vector and hybrid. An answer judge (`claude-haiku-5-5`, own SDK client, D-24) scores answers on a fixed rubric under a spend cap. Deterministic checks need no judge. Decisions: C14, C18, D-18, D-21, D-24.

**Round prep:** none. Dev A owns `build.gradle.kts` this round (lets the `test` source set see `eval` classes).

### Dev A: eval set and retrieval metrics
- **Owns:** `eval/questions.v1.jsonl` (~15 conceptual, ~8 exact-term, 5 unanswerable, 10 ACL questions reused by t12), `eval/README.md` (how labels were made), `src/eval/java/dev/raggateway/eval/{EvalMain, EvalSet, EvidenceMatcher, CorpusEvidenceChecker, RetrievalMetrics, RetrievalEvalRunner, EvalReportWriter}.java`, `eval/reports/` (committed reports), `build.gradle.kts`, and tests.
- **Acceptance criteria:**
  - AC-t13-1: the eval file has ≥ 30 questions; each answerable one has a document id and evidence text that exists verbatim in that corpus document (scripted check against the fetched corpus).
  - AC-t13-2: metric unit tests: on a hand-made ranking, recall@k and MRR equal hand-computed values.
  - AC-t13-3: one command produces a report with rows for 3 modes × 3 chunk sizes and columns recall@1/@5/@10 and MRR.
  - AC-t13-4: the report header names the embedder; with the fake embedder the vector and hybrid rows carry the marker "not a model".
  - AC-t13-9: running the eval twice on the same index gives identical retrieval metrics.

### Dev B: judge and deterministic checks
- **Owns:** `src/eval/java/dev/raggateway/eval/judge/{JudgeClient, JudgeVerdict, SpendCap, AnswerScorer}.java`, `src/eval/java/dev/raggateway/eval/DeterministicChecks.java`, `eval/judge/rubric.md`, `eval/judge/prompt.txt`, and tests. Before writing `JudgeClient`, read the structured-output section of the Claude API reference for Java and record what was used in the PR; validate the verdict JSON against its schema regardless.
- **Acceptance criteria:**
  - AC-t13-6: the judge harness stops when the spend cap is reached and reports how many questions were scored (WireMock-priced test).
  - AC-t13-7: the judge prompt asks for a structured verdict and the harness rejects a malformed verdict rather than counting it.
  - AC-t13-8: the deterministic checks pass: citations ⊆ retrieved chunks for all questions; 5 of 5 unanswerable questions produce the refusal (with a mock generator that follows the instruction).
  - AC-t13-5: the README contains no vector or hybrid recall figure and no answer score until a report with a real embedder and a real judge is committed (reviewer check; in this session those README cells read "pending laptop run").

### Integration step
Merge A, then B. `./gradlew clean test && ./gradlew eval --args="retrieval --embedder fake"`.
- **Runs here:** the eval set and checks; a full keyword-only run (Postgres full-text is real, so these numbers are real and labelled); a vector/hybrid smoke run written to a file marked "fake embedder, not for README"; judge tests against WireMock.
- **Owner only:** index with real `nomic-embed-text`; run all modes and chunk sizes; run answer scoring with her key and spend cap; review the questions and gold evidence; put the numbers in the README.

---

## t14: Final README, tag v1.0, Gate 2 (Round R12)

**Design.** No new behaviour. The README covers both halves with both benchmark tables, trade-offs, limitations, next steps, and the corpus source and CC BY 4.0 attribution; every number maps to a committed result file. Release hygiene is scripted. No tag is created by agents. Decisions: C11, C13, C14, C27.

### Dev A: README and number checks
- **Owns:** `README.md`, extends `scripts/check_readme_numbers.py` to the RAG table.
- **Acceptance criteria:**
  - AC-t14-2: every number in the README maps to a committed result file (same script as AC-t08-2, extended to the RAG table).
  - AC-t14-3: every benchmark row has a "Measured on" value; rows not yet run on the laptop say "pending laptop run" and carry no number.
  - AC-t14-6: the README states the corpus source, commit SHA and CC BY 4.0 attribution; no corpus file is committed.

### Dev B: release hygiene
- **Owns:** `CHANGELOG.md`, `docs/process/RELEASE_CHECKLIST.md`, `scripts/secrets_scan.sh`, `scripts/check_decisions.py`.
- **Acceptance criteria:**
  - AC-t14-5: `docs/process/DECISIONS.md` has an entry for each of C1–C27 and each LLD deviation.
  - AC-t14-7: a secrets scan of the full history finds nothing (same check as AC-t00-2: `git grep` over every commit for the provider key prefix and for the Anthropic key variable assigned a non-empty value).

### Integration step
Merge A, then B. `./gradlew clean test` from a fresh clone (AC-t14-1: `./gradlew clean test` passes from a clean checkout); `bash scripts/secrets_scan.sh`; `python3 scripts/check_decisions.py`; `python3 scripts/check_readme_numbers.py`.
- AC-t14-4: per-task notes for t00–t14 exist with their fixed sections. *(kept outside the repository; checked locally by the owner)*
- **Owner only:** laptop benchmark and eval runs; acceptance for every task; AC-t14-8: `git tag -l v1.0` prints `v1.0` only after AC-t14-1..7 pass and acceptance for t01–t13 is complete; the remaining owner-only criterion (AC-t14-9) is tracked in the private notes.

---

## t00 (already on `main`)

Setup is committed. Criteria checked by the reviewer here: AC-t00-1: `git ls-files` shows `.env.example`; `git check-ignore .env` exits 0. AC-t00-2: `git grep` over the whole history for the provider key prefix or for the Anthropic key variable assigned a non-empty value returns nothing. Owner only: AC-t00-3 (`bash scripts/check_setup.sh` prints no MISSING line), AC-t00-4 (`gh repo view` returns `PUBLIC`), AC-t00-5 (raw `CLAUDE.md` readable without login).
