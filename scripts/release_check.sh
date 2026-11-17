#!/usr/bin/env bash
# rag-gateway: every release check in one command (t14, D-55, AC-t14-11).
#
#   bash scripts/release_check.sh                    # all checks
#   bash scripts/release_check.sh --version 1.0.0    # plus the changelog entry and the build version
#   bash scripts/release_check.sh --skip-build       # everything but the fresh-clone build (reported as SKIP)
#
# Runs, in this order, and prints one OK, FAIL or SKIP line per check (details of a failure indented below it):
#
#   build      `./gradlew clean build` in a fresh clone of HEAD (committed files only; needs Docker for the
#              Testcontainers tests). Log kept in a temp dir and its tail printed on failure.
#   readme     `python3 scripts/check_readme_numbers.py --release`: pending cells outside
#              docs/process/release-pending-allowlist.txt and missing README images block the release.
#   secrets    `scripts/secrets_scan.sh`: every commit of the history.
#   decisions  `scripts/check_decisions.py`: decision index, sections, references, LLD deviation records.
#   mermaid    `scripts/check_mermaid.sh`: the README diagram renders (needs Docker; not running is a FAIL).
#   license    a LICENSE file exists. The licence is the owner's decision (D-55); agents never add one.
#   version    only with --version X: CHANGELOG.md has `## [X]` and build.gradle.kts has `version = "X"`.
#   wording    `scripts/wording_scan.sh`: private terms, from the gitignored private/ pattern file.
#
# The checks after the build read this working tree; a NOTE line says so when it has uncommitted changes.
# Exit code 0 only when every check is OK; any FAIL or SKIP exits 1 (a check that did not run is not a pass).
set -u
cd "$(dirname "$0")/.." || exit 1
REPO=$(pwd)

VERSION=""
SKIP_BUILD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --version) [ $# -ge 2 ] && [ -n "$2" ] || { echo "usage: $0 [--version X] [--skip-build]" >&2; exit 2; }
               VERSION=$2; shift 2 ;;
    --version=*) VERSION=${1#--version=}; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: $0 [--version X] [--skip-build]" >&2; exit 2 ;;
  esac
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failed=0

ok()   { printf 'OK    %-9s %s\n' "$1" "$2"; }
fail() { printf 'FAIL  %-9s %s\n' "$1" "$2"; failed=$((failed + 1)); }
skip() { printf 'SKIP  %-9s %s\n' "$1" "$2"; failed=$((failed + 1)); }
detail() { sed -e '/^[[:space:]]*$/d' -e 's/^/          /' | head -"${2:-40}"; }

# Runs a check script; the script's own last OK/FAIL line becomes the summary, its other output the details.
run_script() { # name, then the command
  local name=$1; shift
  "$@" > "$work/$name.out" 2>&1
  local rc=$?
  local summary
  summary=$(grep -E '^(OK|FAIL|SKIP) ' "$work/$name.out" | tail -1 | sed -E 's/^(OK|FAIL|SKIP) +//')
  [ -n "$summary" ] || summary=$(tail -1 "$work/$name.out")
  if [ "$rc" -eq 0 ]; then
    ok "$name" "$summary"
  else
    fail "$name" "${summary:-exit $rc} (exit $rc)"
    grep -vF -- "$summary" "$work/$name.out" | detail "$name" 40
  fi
}

head_sha=$(git rev-parse --short HEAD)
echo "== release checks for $head_sha${VERSION:+, version $VERSION}"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "NOTE  the working tree has uncommitted changes: the build uses HEAD, the other checks this tree"
fi

# 1. Fresh-clone build.
if [ "$SKIP_BUILD" -eq 1 ]; then
  skip build "fresh-clone build not run (--skip-build)"
else
  clone="$work/clone"
  if ! git clone -q "$REPO" "$clone" 2> "$work/clone.err" \
     || ! git -C "$clone" checkout -q --detach "$(git rev-parse HEAD)" 2>> "$work/clone.err"; then
    fail build "could not clone HEAD"; detail build < "$work/clone.err"
  else
    start=$(date +%s)
    if (cd "$clone" && ./gradlew clean build --no-daemon --console=plain) > "$work/build.log" 2>&1; then
      tests=$(find "$clone/build/test-results" -name 'TEST-*.xml' 2>/dev/null | wc -l | tr -d ' ')
      ok build "./gradlew clean build in a fresh clone of $head_sha ($tests test classes, $(( $(date +%s) - start ))s)"
    else
      fail build "./gradlew clean build failed in a fresh clone of $head_sha; last lines:"
      grep -vE '^[[:space:]]*$' "$work/build.log" | tail -25 | detail build 25
    fi
  fi
fi

# 2. README numbers in release mode (Dev A's flag; reported clearly when this tree's checker lacks it).
if python3 scripts/check_readme_numbers.py --help 2>/dev/null | grep -q -- '--release'; then
  run_script readme python3 scripts/check_readme_numbers.py --release
else
  fail readme "scripts/check_readme_numbers.py has no --release mode in this tree (t14 Dev A adds it)"
fi

# 3-5. History secrets, decision index, Mermaid.
run_script secrets bash scripts/secrets_scan.sh
run_script decisions python3 scripts/check_decisions.py
run_script mermaid bash scripts/check_mermaid.sh

# 6. Licence.
if [ -f LICENSE ]; then
  ok license "LICENSE present ($(head -1 LICENSE | sed 's/^[[:space:]]*//' | cut -c1-60))"
else
  fail license "no LICENSE file: owner decision, D-55"
fi

# 7. Version and changelog.
if [ -n "$VERSION" ]; then
  problems=()
  grep -qF "## [$VERSION]" CHANGELOG.md 2>/dev/null || problems+=("CHANGELOG.md has no '## [$VERSION]' entry")
  grep -qE "^[[:space:]]*version[[:space:]]*=[[:space:]]*\"$(printf '%s' "$VERSION" | sed 's/[].[^$*\\/]/\\&/g')\"" build.gradle.kts \
    || problems+=("build.gradle.kts has $(grep -oE '^[[:space:]]*version[[:space:]]*=.*' build.gradle.kts | head -1 | sed 's/^[[:space:]]*//' || echo 'no version'), not version = \"$VERSION\"")
  if [ ${#problems[@]} -eq 0 ]; then
    ok version "CHANGELOG.md has '## [$VERSION]' and build.gradle.kts has version = \"$VERSION\""
  else
    fail version "version $VERSION is not consistent:"
    printf '%s\n' "${problems[@]}" | detail version
  fi
fi

# 8. Wording.
run_script wording bash scripts/wording_scan.sh

echo
if [ "$failed" -eq 0 ]; then
  echo "== all release checks OK"
  exit 0
fi
echo "== $failed check(s) not OK"
exit 1
