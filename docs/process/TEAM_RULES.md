# Team rules

For the two developers and the reviewer. The plan is `WORK_PLAN.md`; the reasons are `DECISIONS.md`; the contracts are the javadoc in `src/main/java/dev/raggateway`.

## Branches, worktrees, commits
- One worktree per developer per task, created from `main` after the round-prep commit: `git worktree add ../rg-t05-a -b t05-a`. Branch names: `tNN-a` (Dev A) and `tNN-b` (Dev B); in a two-task round each developer uses the branch of their own task (e.g. `t06-a`, `t07-b`). The reviewer merges each with `--no-ff`.
- Small commits, one concern each. Message: `tNN: <imperative summary>` (scaffold and process commits use a type prefix such as `docs(process):`). Body explains why when it is not obvious.
- Every commit message ends with exactly these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01EhfLAPe7Y6ysHFpGknbLJS
  ```
- Rebase on `main`; never merge `main` into a task branch. The reviewer merges to `main` in task order (A before B; lower task before higher). **No pushes**: the reviewer hands `main` to the owner, who pushes.

## Running things
- Tests: `./gradlew test` (all), `./gradlew test --tests '*CacheFilterTest'` (one class). Your branch must be green on its own before you ask for review. Use `./gradlew`, never a system Gradle or Maven.
- Tests need Docker (Testcontainers). If `docker info` fails: `(dockerd > /tmp/dockerd.log 2>&1 &) ; sleep 8`.
- Local stack: `docker compose up -d` (Postgres + Redis), `docker compose --profile obs up -d` (adds Prometheus + Grafana). App: `./gradlew bootRun` (runs with `-Xmx512m`).
- Integration tests extend `AbstractIntegrationTest` (full app, shared Postgres and Redis), `AbstractAppTest` (full app, shared Postgres, **you register your own Redis**; use it only to stop or isolate Redis), `AbstractPostgresTest` or `AbstractRedisTest` (no Spring). Never register Redis properties in a subclass of `AbstractIntegrationTest`: the base's registration wins silently (D-35). Providers in tests are WireMock or `FakeLlmProvider`. No fixed sleeps longer than the behaviour under test; use Awaitility. Log-row assertions in HTTP tests select by the test's own request ids (from `X-Request-Id`), never "all rows" or "exactly N rows in the table": a row from an earlier test's slow stream can land after the next test's cleanup.

## What you may not touch
- Files another developer owns this round, the round's migrations, and the contract types in `core`, `pipeline` (interfaces and `FilterChain`/`ForwardingSink`), `error`, `provider/LlmProvider`, `provider/ProviderException`, and the interfaces in `auth`, `ratelimit`, `cache`, `routing`, `validation`, `rag`, `metrics/MetricNames`. If a contract is wrong, stop and tell the reviewer; the change is recorded in `DECISIONS.md` first.
- `CLAUDE.md`, `README.md` (except in t08/t14 by its owner), `docs/HLD.md`, `docs/LLD.md`, `docs/CONCEPTS.md`, `docs/ROADMAP.md`, `docs/process/DECISIONS.md`, `.gitignore`, `gradle/libs.versions.toml` (version changes go through the reviewer).
- `.tracker/`: never write to it. `private/` and `CLAUDE.local.md`: never commit them, never `git add -f`.

## Environment limits (this build environment)
- Linux container, 4 vCPU / 15 GB, Docker available. Maven Central and Docker Hub reachable.
- No Ollama, no provider API key, no GitHub release downloads. Never call a real provider from tests or benchmarks; use WireMock.
- Numbers measured here are labelled "cloud container, 4 vCPU / 15 GB", never as laptop numbers.

## Say so and stop
If you cannot verify something (no Ollama, no key, a flaky timing, an external host blocked), write that in your branch notes and stop at that point. Do not fake a result, do not weaken a test until it passes, do not mark an owner-only check as passed. A skipped check with a reason is acceptable; an invented one is not.

## Secrets
No keys, tokens or passwords in code, tests, config, docs, logs or commit messages. Secrets come from `.env` (gitignored); `.env.example` lists names only. Test keys are obviously fake strings created in the test. Never print a presented API key; log the key id.

## Public wording
The repository is public. Code, comments, docs and commit messages contain engineering content only: what it does, why, what was rejected, what it costs. No personal context, no career or schedule content, no names of people or employers. Private task notes (not committed) live outside the repository and are referred to only by that phrase.

## Definition of done for a branch
Owned files only; tests for every acceptance criterion assigned to you (named after the criterion where practical, e.g. `ac_t05_7_fiftyConcurrentMissesMakeOneCall`); `./gradlew test` green; any decision you had to make is written in the branch notes for the reviewer; private task notes drafted if the task names you.
