# rag-gateway

An LLM gateway (OpenAI-compatible `/v1/chat/completions`) with a RAG endpoint on top, built in Java 21 + Spring Boot 3. Every component has a stated reason to exist and a rejected alternative, and every number in the README is measured, never estimated.

## Read these first
- `docs/ROADMAP.md` — scope, the task plan (t00–t14), stack, hardware constraints
- `docs/HLD.md` — system design: requirements, estimates, API, high-level design, deep dives
- `docs/LLD.md` — classes, interfaces, filter chain, rate limiter (Lua), circuit breaker, router, cache, RAG SQL, outbox
- `docs/CONCEPTS.md` — plain-language concepts and the trade-offs table
- `docs/process/DECISIONS.md` — architecture decisions, including where they override the docs above

Build to the LLD. If you want to deviate from it, say so and why before writing the code.

## Overrides (these win over the docs)
- **Embedding size is 768.** Embeddings come from Ollama `nomic-embed-text`, so chunk vectors are `vector(768)`.
- **Hardware: 8 GB MacBook Air.** Default `docker compose up` = Postgres (`pgvector/pgvector:pg16`) + Redis (`redis:7-alpine`) only. Prometheus + Grafana go under the `obs` compose profile. The app runs from the IDE with `-Xmx512m` and `spring.threads.virtual.enabled=true`.
- **Local models:** `llama3.2:1b` as the fallback provider, `nomic-embed-text` for embeddings. Never suggest a larger local model.
- **Benchmarks** run with k6 against a WireMock mock provider (fixed latency), and every number is labelled with the hardware.
- Secrets live in `.env` (gitignored). Never commit keys.

## How we work
- Before each task: explain the design, the alternatives and why this one. Wait for my go-ahead.
- Flag every significant trade-off: rate-limit algorithm, cache key, fallback trigger, retries mid-stream, chunk size, ACL pre- vs post-filter, sync vs async cost logging.
- Small commits with clear messages. A task ends with passing tests and a commit.
- Don't over-engineer. Tell me when I'm wrong.
- Personal notes and local instructions live in `CLAUDE.local.md` and `private/` (both gitignored). Never commit them.

## Session logging
Each coding session is logged to `.tracker/sessions.jsonl`, which an external progress tracker reads from the default branch.

- At the start of a session, run `date '+%Y-%m-%d %H:%M'` (IST) and note it. Ask which task (t00–t14) we're on if it isn't obvious.
- At the end of a session, or when I say "log it", append ONE line to `.tracker/sessions.jsonl` (create it if missing):
  `{"id":"<YYYY-MM-DD>-<task>-<n>","date":"<YYYY-MM-DD IST>","project":"rag-gateway","task":"<t00..t14>","minutes":<int>,"status":"In progress|Done","demo":"No|Yes","note":"<one line: what works now, what's left>"}`
  - `<n>`: 1 for the first session on that task that day, then 2, 3…
  - `minutes`: real elapsed time since the session start, minus breaks I mention. If I give a number, use mine. Never estimate otherwise; ask.
  - `status`: "Done" only when I say the task is done AND its tests pass.
  - `demo`: "Yes" only when I have seen the feature working end to end.
  - `note`: facts about the code only.
- Commit the line with the code (`chore(tracker): log session <id>`) and push. If push fails, tell me: an unpushed line never reaches the tracker.
- Never edit or delete earlier lines; add a new one to correct something.
