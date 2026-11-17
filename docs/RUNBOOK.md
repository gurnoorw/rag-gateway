# Release runbook: from a fresh laptop to v1.0

The steps that need the target laptop (8 GB MacBook Air), real models or an Anthropic API key, in order. Each step has the exact command and what "done" looks like. Everything else is already measured, tested and checked in the build environment ([DECISIONS.md](process/DECISIONS.md) D-21, D-55).

Run from the repository root. Secrets go in `.env` (gitignored, copied from `.env.example`) and are never committed. Commands that read `.env` assume it is exported in the shell, e.g. `set -a; . ./.env; set +a`.

Steps 3 and 4 write files meant for committing, and both refuse to run on a tree with uncommitted changes (a results file names the commit that produced it). Commit or stash first; `--allow-dirty` exists for probes only and writes under the gitignored `bench/results/` or `build/eval/`.

## 0. Prerequisites

```bash
bash scripts/check_setup.sh
ollama pull llama3.2:1b && ollama pull nomic-embed-text
cp -n .env.example .env        # then fill in GATEWAY_CLAUDE_API_KEY and EVAL_JUDGE_API_KEY
```

Done when `check_setup.sh` prints no `MISSING` line and `ollama list` shows both models. No other local model is used.

## 1. Stack, keys and corpus (real embeddings)

```bash
docker compose up -d                                  # Postgres + Redis only
set -a; . ./.env; set +a
./gradlew bootRun                                      # terminal 1; real Claude + Ollama, gateway.rag.embedder=ollama
scripts/seed-demo.sh                                   # terminal 2; store the acme admin key
scripts/seed-eval.sh                                   # store the printed key as EVAL_GATEWAY_API_KEY in .env
python3 scripts/corpus/fetch_corpus.py
RAG_ADMIN_KEY=<acme admin key> python3 scripts/corpus/load_corpus.py --wait 3600
```

Done when:
- `fetch_corpus.py` reports 256/256 files with HTTP 200;
- `load_corpus.py` reports all 243 documents indexed, and notes the elapsed time (the real-embedding indexing time);
- `SELECT count(*), min(vector_dims(embedding)) FROM chunks WHERE collection_id = 'k8s-docs'` gives about 3,956 chunks and 768.

## 2. Live Claude check

```bash
python3 -m venv /tmp/rg-openai && /tmp/rg-openai/bin/pip install openai
RAG_GATEWAY_API_KEY=<acme admin key> /tmp/rg-openai/bin/python scripts/openai_client_check.py \
    --base-url http://localhost:8080/v1 --model claude-haiku-5-5
```

Done when both checks pass (a streamed answer with usage, then a 401 for a wrong key). This is the only step that calls Claude through the gateway, so it costs a few tokens.

## 3. Benchmarks on the laptop

Stop `bootRun` first: the harness starts its own gateway at `-Xmx512m` and the mocks.

```bash
bash bench/run-all.sh --measured-on "8 GB MacBook Air"
```

Done when `bench/summary/<run_id>.json` exists and its `measured_on` is `8 GB MacBook Air`. Commit it; the README rows are filled in step 7.

## 4. Retrieval and ACL eval with real embeddings

App running again (step 1), corpus loaded.

```bash
./gradlew eval --args="retrieval --modes keyword,vector,hybrid --embedder nomic-embed-text --measured-on '8 GB MacBook Air'"
```

Done when `eval/results/<run_id>.json` is written with 9 retrieval rows and an `acl` section in hybrid mode, and the command exits 0 (it exits 1 when any chunk leaked). Check that `unauthorised_leaked_chunks` is 0. Never run `--embedder fake` into tenant `eval`; it defaults to `eval-fake`, so leave the default. The question set and metrics are described in [eval/README.md](../eval/README.md).

## 5. Answer eval: collect, dry run, calibration, judge

App running (step 1). `collect` asks the RAG endpoint with the `eval` tenant's key from step 1, read from `EVAL_GATEWAY_API_KEY`; the judge reads only `EVAL_JUDGE_API_KEY`.

```bash
set -a; . ./.env; set +a
./gradlew eval --args="answers collect --questions eval/questions.v1.jsonl --out build/eval/answers.jsonl"
./gradlew eval --args="answers score --answers build/eval/answers.jsonl --dry-run"
```

Done when `collect` wrote one line per non-ACL question and the dry run's "estimated worst-case cost" is under the cap in [`eval/judge/judge.properties`](../eval/judge/judge.properties).

Calibration: before running the judge, write `eval/judge/calibration.jsonl` with 10 answers from `build/eval/answers.jsonl` that you graded yourself, one `{"id": "q01", "correctness": 2}` per line (0 wrong, 1 partly right, 2 correct; the rubric is [eval/judge/rubric.md](../eval/judge/rubric.md)).

```bash
./gradlew eval --args="answers score --answers build/eval/answers.jsonl --confirm-spend \
    --calibration eval/judge/calibration.jsonl --into eval/results/<run_id from step 4>.json"
```

Done when the command reports "scored N of M", the spend under the cap and the agreement with your labels, and the step-4 results file now has an `answers` section (the command refuses a file that already has one). Commit the results file and the calibration labels. D-55 recommends publishing answer scores only with an agreement of at least 8 of 10 (owner decision 3); below that, fix the rubric or prompt and re-run, or leave the answer rows pending with the agreement stated.

## 6. Grafana screenshot

```bash
docker compose --profile obs up -d
bash scripts/check_obs.sh
```

With the app taking traffic (for example `scripts/demo.sh` against the mock profile, or a few RAG queries), capture the `rag-gateway` dashboard ([`ops/grafana/dashboards/rag-gateway.json`](../ops/grafana/dashboards/rag-gateway.json)) at http://localhost:3000 as `docs/img/grafana-dashboard.png`.

Done when `check_obs.sh` passes and the image is committed. The README links it in step 7; the line to paste is in [docs/img/README.md](img/README.md).

## 7. README numbers

Fill the README from the files of steps 3–5, copying each value by hand (D-55 rejects auto-filling, so a person looks at every number):
- **Measured table:** each row ending in "laptop" gets the value from `bench/summary/<run_id>.json` at its Source key, Measured on `8 GB MacBook Air` (the file's `measured_on`, exactly) and the Run id. Other laptop rows may be added the same way; the container rows stay.
- **Eval table:** each "pending laptop run" row gets the value, the file's `measured_on` and the Run id from `eval/results/<run_id>.json`; Embedder stays `nomic-embed-text`.
- **Dashboard:** add the image line from step 6 to the Dashboard section.
- Rewrite any prose that says a row is pending or not yet measured (S6b, the eval notes, Status, Limitations).

```bash
python3 scripts/check_readme_numbers.py
python3 scripts/check_readme_numbers.py --release
```

Done when both pass. `--release` fails on any "pending" cell whose Metric text [`docs/process/release-pending-allowlist.txt`](process/release-pending-allowlist.txt) doesn't name; to ship with a cell still pending, add its Metric text to that file, one per line, and commit it (owner decision 2 in D-55). Both modes also fail on a relative link or image in the README or this runbook that does not resolve to a committed file.

## 8. Release checks, version, tag

After the acceptance of every task:

```bash
bash scripts/release_check.sh                      # every check except the version: run until green
sed -i.bak 's/^version = ".*"/version = "1.0.0"/' build.gradle.kts && rm build.gradle.kts.bak
git commit -am "release: 1.0.0"
bash scripts/release_check.sh --version 1.0.0      # the same checks, plus version and changelog entry
git tag -a v1.0 -m "rag-gateway 1.0.0"
git push origin main && git push origin v1.0
```

Done when `release_check.sh --version 1.0.0` prints OK for every check and `git tag -l v1.0` prints `v1.0`, with the tag on the remote. The checks are:
- a fresh-clone `./gradlew clean build`;
- README numbers in release mode;
- a secrets scan of the full history;
- `scripts/check_decisions.py`;
- the Mermaid diagrams;
- `LICENSE` present;
- with `--version`: `CHANGELOG.md` has that entry and `build.gradle.kts` has that version;
- the wording scan, which needs the private pattern file and is skipped with a notice if it is absent.

Agents never create or push the tag (C13).
