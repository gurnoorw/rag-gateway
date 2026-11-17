#!/usr/bin/env bash
# rag-gateway: no key in any commit of the history (t14, D-55, AC-t14-7).
#
#   bash scripts/secrets_scan.sh             # scans this repository
#   bash scripts/secrets_scan.sh path/to/repo  # scans another repository (used by scripts/tests)
#
# Every commit reachable from any ref (`git rev-list --all`) is searched with `git grep`, so a key that was
# committed and later deleted is still found. Commit messages are searched too. It looks for:
#
#   provider-key   the provider key prefix. The prefix is assembled from parts below, so this file never
#                  contains it and the scan cannot match itself.
#   gateway-key    a gateway key literal: `rgw_` followed by 43 base64url characters (32 random bytes).
#   key-variable   a key variable named in `.env.example` (names ending in _KEY, _TOKEN or _SECRET) assigned a
#                  non-empty value: `NAME=value`, `NAME: value`, `"NAME": "value"`, `${NAME:-value}`. A name with
#                  nothing after `=` (as in `.env.example`) or a reference such as `${NAME}` or `$(...)` is not a
#                  value. Passwords are left out on purpose: the compose and application defaults for the local
#                  Postgres and Grafana are documented development defaults, not secrets.
#
# A finding prints the kind, the first commit seen and the file and line, never the matched text, so the
# report itself does not spread a key. Binary files are skipped (`git grep -I`).
#
# Exit code 0 when nothing is found, 1 on any finding, 2 when the path is not a git repository.
set -u
if [ $# -gt 0 ]; then REPO=$1; else REPO=$(cd "$(dirname "$0")/.." && pwd); fi
git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1 || { echo "FAIL  not a git repository: $REPO"; exit 2; }

p1=sk; p2=ant
PROVIDER_PREFIX="${p1}-${p2}-"
GATEWAY_KEY='rgw_[A-Za-z0-9_-]{43}'

# Key variable names from .env.example in the scanned tree (HEAD's copy when the working tree has none).
env_example=$( { cat "$REPO/.env.example" 2>/dev/null || git -C "$REPO" show HEAD:.env.example 2>/dev/null; } \
  | sed -nE 's/^[[:space:]]*(export[[:space:]]+)?([A-Z][A-Z0-9_]*(_KEY|_TOKEN|_SECRET))[[:space:]]*=.*/\2/p' | sort -u)
names=$(printf '%s\n' $env_example | paste -sd'|' -)
if [ -n "$names" ]; then
  # The name, an optional closing quote, then `=`, `:` or `:-`, then a value that starts with a word character
  # (so `$`, `<`, `}` and an empty value do not count).
  KEY_VARIABLE="($names)[\"']?[[:space:]]*(=|:-|:)[[:space:]]*[\"']?[A-Za-z0-9_]"
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git -C "$REPO" rev-list --all > "$work/revs"
commits=$(wc -l < "$work/revs" | tr -d ' ')

# grep_history KIND PATTERN: every matching line of every commit, one finding per (file, line text).
grep_history() {
  local kind=$1 pattern=$2
  [ -s "$work/revs" ] || return 0
  # xargs splits a long history into several git grep calls; each exits 1 when its batch has no match.
  xargs -r -n 200 git -C "$REPO" grep -I -n -E -e "$pattern" < "$work/revs" 2>/dev/null \
    | awk -v kind="$kind" '{
        rev = substr($0, 1, index($0, ":") - 1); rest = substr($0, index($0, ":") + 1)
        path = substr(rest, 1, index(rest, ":") - 1); rest = substr(rest, index(rest, ":") + 1)
        line = substr(rest, 1, index(rest, ":") - 1); text = substr(rest, index(rest, ":") + 1)
        if (!((path SUBSEP text) in seen)) { seen[path SUBSEP text] = 1; printf "%s\t%s\t%s:%s\n", kind, substr(rev, 1, 7), path, line }
      }' >> "$work/findings"
}

# grep_messages KIND PATTERN: commit messages (subject and body).
grep_messages() {
  local kind=$1 pattern=$2
  git -C "$REPO" log --all --format='@@secrets-scan@@ %h%n%B' 2>/dev/null \
    | awk '/^@@secrets-scan@@ / { rev = $2; next } { print rev "\t" $0 }' \
    | grep -E -e "$pattern" | cut -f1 | sort -u \
    | awk -v kind="$kind" '{ printf "%s\t%s\tcommit message\n", kind, $1 }' >> "$work/findings"
}

: > "$work/findings"
grep_history provider-key "$PROVIDER_PREFIX"
grep_history gateway-key "$GATEWAY_KEY"
[ -n "$names" ] && grep_history key-variable "$KEY_VARIABLE"
grep_messages provider-key "$PROVIDER_PREFIX"
grep_messages gateway-key "$GATEWAY_KEY"
[ -n "$names" ] && grep_messages key-variable "$KEY_VARIABLE"

found=$(wc -l < "$work/findings" | tr -d ' ')
variables=$(printf '%s\n' $env_example | grep -c . || true)
if [ "$found" -eq 0 ]; then
  echo "OK    secrets scan: $commits commits and their messages, no provider key, gateway key or assigned key variable ($variables key variables from .env.example)"
  exit 0
fi
echo "FAIL  secrets scan: $found finding(s) in $commits commits (matched text not shown):"
sort -u "$work/findings" | awk -F'\t' '{ printf "        %-13s %s  %s\n", $1, $2, $3 }'
exit 1
