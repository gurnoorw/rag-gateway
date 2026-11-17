#!/usr/bin/env bash
# rag-gateway: the public tree holds engineering wording only (t14, D-55, AC-t14-12; TEAM_RULES "Public wording").
#
#   bash scripts/wording_scan.sh               # scans this repository's tracked files
#   bash scripts/wording_scan.sh path/to/repo  # scans another repository (used by scripts/tests)
#
# The terms to look for live in `private/wording-patterns.txt` of this checkout, which is gitignored and kept by
# the owner: a committed list of terms to avoid would itself put them in the public repository. No term list is
# committed anywhere. WORDING_PATTERNS_FILE points at another pattern file (used by the tests with a temporary
# file). Without a pattern file the scan prints a SKIP line ("skipped: no private pattern file") and exits 0, so
# it can run anywhere; release_check.sh reports that SKIP as a failure (a check that did not run is not a pass).
#
# Pattern file: one term per line, matched as a fixed string, case-insensitively; blank lines and lines
# starting with `#` are ignored. Every tracked file in the working tree is searched (`git grep`, binary files
# skipped). A finding prints the file, the line and the pattern's line number in the pattern file, never the
# term itself, so the output can be shared without repeating the private list.
#
# Exit code 0 when nothing is found or the scan is skipped, 1 on any finding, 2 when the path is not a git
# repository or the pattern file has no terms.
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
if [ $# -gt 0 ]; then REPO=$1; else REPO=$HERE; fi
PATTERNS=${WORDING_PATTERNS_FILE:-$HERE/private/wording-patterns.txt}

if [ ! -f "$PATTERNS" ]; then
  echo "SKIP  wording scan skipped: no private pattern file ($PATTERNS)"
  exit 0
fi
git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1 || { echo "FAIL  not a git repository: $REPO"; exit 2; }

terms=0
findings=0
report=""
lineno=0
while IFS= read -r term || [ -n "$term" ]; do
  lineno=$((lineno + 1))
  term=${term%$'\r'}
  case "$term" in ''|'#'*) continue ;; esac
  [ -n "${term//[[:space:]]/}" ] || continue
  terms=$((terms + 1))
  # file:line for each tracked file line that contains the term; the matched text is cut off.
  hits=$(git -C "$REPO" grep -I -n -i -F -e "$term" 2>/dev/null | awk -F: '{ print $1 ":" $2 }')
  if [ -n "$hits" ]; then
    while IFS= read -r hit; do
      findings=$((findings + 1))
      report+="        $hit  (pattern line $lineno)"$'\n'
    done <<< "$hits"
  fi
done < "$PATTERNS"

if [ "$terms" -eq 0 ]; then
  echo "FAIL  wording scan: $PATTERNS has no terms"
  exit 2
fi
files=$(git -C "$REPO" ls-files | wc -l | tr -d ' ')
if [ "$findings" -eq 0 ]; then
  echo "OK    wording scan: $terms private terms, none in $files tracked files"
  exit 0
fi
echo "FAIL  wording scan: $findings line(s) match a private term (terms not shown):"
printf '%s' "$report"
exit 1
