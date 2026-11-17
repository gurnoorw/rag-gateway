# Release runbook: from a fresh laptop to v1.0

The steps that need the target laptop (8 GB MacBook Air), real models or an Anthropic API key, in order. Each step has the exact command and what "done" looks like. Everything else is already measured, tested and checked in the build environment (DECISIONS.md D-21, D-55).

Run from the repository root. Secrets go in `.env` (gitignored, copied from `.env.example`) and are never committed. Commands that read `.env` assume it is exported in the shell, e.g. `set -a; . ./.env; set +a`.

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

Done when `bench/summary/<run_id>.json` exists. Commit it; the laptop rows are added in step 7.

## 4. Retrieval and ACL eval with real embeddings

App running again (step 1), corpus loaded.

```bash
./gradlew eval --args="retrieval --modes keyword,vector,hybrid --embedder nomic-embed-text --measured-on '8 GB MacBook Air'"
```

Done when `eval/results/<run_id>.json` is written with 9 retrieval rows and an `acl` section in hybrid mode. Check that `unauthorised_leaked_chunks` is 0. Never run `--embedder fake` into tenant `eval`; it defaults to `eval-fake`, so leave the default.

## 5. Answer eval: collect, dry run, calibration, judge

```bash
./gradlew eval --args="answers collect --out build/eval/answers.jsonl"
./gradlew eval --args="answers score --answers build/eval/answers.jsonl --dry-run"
```

Done when `collect` wrote one line per non-ACL question and the dry run's "estimated worst-case cost" is under the cap in `eval/judge/judge.properties`.

Calibration: before running the judge, write `eval/judge/calibration.jsonl` with 10 answers you graded yourself (0 wrong, 1 partly, 2 correct; the format is in `eval/README.md`).

```bash
./gradlew eval --args="answers score --answers build/eval/answers.jsonl --confirm-spend \
    --calibration eval/judge/calibration.jsonl --into eval/results/<run_id from step 4>.json"
```

Done when the command reports "scored N of M", the spend under the cap and the agreement with your labels. If agreement is low, fix the rubric or prompt and re-run before publishing any answer score.

## 6. Grafana screenshot

```bash
docker compose --profile obs up -d
bash scripts/check_obs.sh
```

With the app taking traffic (for example `scripts/demo.sh` against the mock profile, or a few RAG queries), capture the `rag-gateway` dashboard at http://localhost:3000 as `docs/img/grafana-dashboard.png`.

Done when `check_obs.sh` passes and the image is committed.

## 7. README numbers

Add the laptop benchmark rows from step 3 and the eval rows from steps 4–5. Every value is copied from its results file, with the labels "8 GB MacBook Air" and `nomic-embed-text`. Then:

```bash
python3 scripts/check_readme_numbers.py
python3 scripts/check_readme_numbers.py --release
```

Done when both pass. `--release` fails on any "pending" cell that `docs/process/release-pending-allowlist.txt` doesn't name.

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
