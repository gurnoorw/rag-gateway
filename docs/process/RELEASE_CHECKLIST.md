# Release checklist: v1.0

What must be true before the `v1.0` tag, in order, and how each item is checked. The commands are in
`docs/RUNBOOK.md`; the reasons are D-55 in `DECISIONS.md`. Tick an item only when its check has passed; an item
that could not be checked stays open with the reason written next to it.

Who: **owner** items are taken only by the owner (C13, D-55); **anyone** items can be run in the build environment
or on the laptop.

## 1. Code and tests (anyone)
- [ ] Every task t01–t14 merged to `main` with its review rulings recorded in `DECISIONS.md`.
- [ ] `./gradlew clean build` passes twice in a row on `main`.
- [ ] `python3 -m unittest discover -s scripts/tests -v` passes (Docker available, so the Mermaid tests run).
- [ ] Acceptance of every task t01–t13 (**owner**).

## 2. Laptop and live steps (owner, `docs/RUNBOOK.md` steps 0–7)
- [ ] Step 0: `check_setup.sh` shows no `MISSING` line; `llama3.2:1b` and `nomic-embed-text` pulled, no other local model.
- [ ] Step 1: corpus indexed with real embeddings; chunk vectors have 768 dimensions.
- [ ] Step 2: live Claude check passes (streamed answer with usage, 401 for a wrong key).
- [ ] Step 3: laptop bench committed as `bench/summary/<run_id>.json`, labelled "8 GB MacBook Air".
- [ ] Step 4: retrieval and ACL eval with `nomic-embed-text` committed; `unauthorised_leaked_chunks` is 0.
- [ ] Step 5: answer scores published only with calibration agreement of at least 8 of 10; otherwise the answer
      rows stay pending with the agreement stated (D-55, owner decision 3).
- [ ] Step 6: `check_obs.sh` passes; `docs/img/grafana-dashboard.png` committed.
- [ ] Step 7: README laptop and eval rows copied by hand from their results files;
      `python3 scripts/check_readme_numbers.py --release` passes.

## 3. Owner decisions (D-55)
- [x] Licence chosen and a `LICENSE` file committed: Apache-2.0, chosen by the owner (D-57).
- [ ] Pending cells allowed at release written to `docs/process/release-pending-allowlist.txt`, or none (the
      default: the file stays empty).
- [ ] `private/wording-patterns.txt` up to date (gitignored; never committed), so the wording scan runs instead
      of being skipped.

## 4. Release checks (anyone; `bash scripts/release_check.sh`)
One OK line per check; any FAIL or SKIP blocks the release.

| Check | Command it runs | Fails when |
|---|---|---|
| build | `./gradlew clean build` in a fresh clone of HEAD | any compile or test failure (AC-t14-1) |
| readme | `scripts/check_readme_numbers.py --release` | a number without its results file, a pending cell not allowlisted, a missing image (AC-t14-2, -3, -10) |
| secrets | `scripts/secrets_scan.sh` | a provider key prefix, a gateway key literal or an assigned key variable in any commit or commit message (AC-t14-7) |
| decisions | `scripts/check_decisions.py` | a C1–C27 or indexed D entry without a section, a section without an index row, a reference to a missing entry, an LLD deviation record that does not name its entry (AC-t14-5) |
| mermaid | `scripts/check_mermaid.sh` | a README diagram that does not render, or Docker unavailable |
| license | `test -f LICENSE` | no licence file (owner decision) |
| version | with `--version X`: `## [X]` in `CHANGELOG.md`, `version = "X"` in `build.gradle.kts` | either is missing |
| wording | `scripts/wording_scan.sh` | a tracked file contains a term from the private pattern file (AC-t14-12) |

- [ ] `bash scripts/release_check.sh` prints OK for every check.
- [ ] `git ls-files data/corpus` is empty; the README states the corpus source, commit and CC BY 4.0 attribution
      (AC-t14-6).
- [ ] `git ls-files private CLAUDE.local.md .env` is empty.

## 5. Version and tag (owner, `docs/RUNBOOK.md` step 8)
- [ ] Release commit: `build.gradle.kts` `version = "1.0.0"`, and the `CHANGELOG.md` heading
      `## [1.0.0] - Unreleased` given the release date (`## [1.0.0] - YYYY-MM-DD`).
- [ ] `bash scripts/release_check.sh --version 1.0.0` prints OK for every check, including `version`.
- [ ] `git tag -a v1.0` on that commit; `git tag -l v1.0` prints `v1.0`; `main` and the tag pushed (AC-t14-8).
- [ ] After the tag: a new `## [Unreleased]` section at the top of `CHANGELOG.md` and the next snapshot version.
