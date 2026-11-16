#!/usr/bin/env bash
# rag-gateway: the README quickstart works on a clean checkout (t08, AC-t08-9).
#
#   bash scripts/quickstart_check.sh            # clean clone of HEAD in a temp dir, then the quickstart
#   bash scripts/quickstart_check.sh --here     # the quickstart in this working tree
#   bash scripts/quickstart_check.sh --print    # only print the commands it would run
#
# The commands are read from the README itself (the bash block between <!-- quickstart:begin --> and
# <!-- quickstart:end -->), so the check and the README cannot drift apart. It requires at most 4 commands,
# exactly one of them `./gradlew bootRun` and the last one a `curl`. Then it runs them in order: the
# bootRun command in the background (log in the temp dir) until /actuator/health answers, the others in one
# shell so an exported variable carries over, and expects the final curl to get HTTP 200 with a chat
# completion body.
#
# A clean clone means committed files only: uncommitted changes are not part of the check. The clone runs
# under its own compose project (COMPOSE_PROJECT_NAME=rag-gateway-quickstart, which also reaches the
# `docker compose exec` inside seed-demo.sh), so it never replaces or stops another stack's containers; with
# a stack already publishing the same ports it fails instead. At the end its own project is taken down with
# its volumes (a clean checkout has no data worth keeping). --here uses the working tree's default project
# and leaves the stack running.
#
# Needs: git, docker compose, curl, a JDK 21, and ports 8080, 5432, 6379 and the mock ports free.
# Exit code 0 on a 200, 1 otherwise.
set -u
cd "$(dirname "$0")/.." || exit 1
REPO=$(pwd)

MODE=clone
case "${1:-}" in
  --here) MODE=here ;;
  --print) MODE=print ;;
  "") ;;
  *) echo "usage: $0 [--here|--print]" >&2; exit 1 ;;
esac
HEALTH_URL=${HEALTH_URL:-http://localhost:8080/actuator/health}
START_TIMEOUT=${START_TIMEOUT:-300}   # seconds: a clean clone compiles before it starts

# ------------------------------------------------------------------ extract
extract() { # one command per line: the bash block between the markers, continuations joined, comments dropped
  awk '
    /<!-- quickstart:begin -->/ { inq = 1; next }
    /<!-- quickstart:end -->/   { inq = 0 }
    inq && /^```bash/ { inb = 1; next }
    inq && inb && /^```/ { inb = 0; next }
    inq && inb {
      line = $0
      if (line ~ /\\$/) { sub(/\\$/, "", line); acc = acc line; next }
      line = acc line; acc = ""
      if (line ~ /^[[:space:]]*(#|$)/) next
      print line
    }
  ' "$1"
}

mapfile -t CMDS < <(extract README.md)
problems=()
[ "${#CMDS[@]}" -ge 1 ] || problems+=("no quickstart commands between the markers in README.md")
[ "${#CMDS[@]}" -le 4 ] || problems+=("${#CMDS[@]} commands; the quickstart allows at most 4")
boot_index=-1
for i in "${!CMDS[@]}"; do
  case "${CMDS[$i]}" in *"./gradlew bootRun"*) [ "$boot_index" -lt 0 ] && boot_index=$i || problems+=("two bootRun commands") ;; esac
done
[ "$boot_index" -ge 0 ] || problems+=("no ./gradlew bootRun command")
last=$(( ${#CMDS[@]} - 1 ))
case "${CMDS[$last]:-}" in curl\ *) ;; *) problems+=("the last command is not a curl") ;; esac

if [ "$MODE" = print ] || [ "${#problems[@]}" -gt 0 ]; then
  for i in "${!CMDS[@]}"; do printf '%d: %s\n' "$((i + 1))" "${CMDS[$i]}"; done
fi
if [ "${#problems[@]}" -gt 0 ]; then
  for p in "${problems[@]}"; do echo "FAIL  $p"; done
  exit 1
fi
[ "$MODE" = print ] && exit 0

# ------------------------------------------------------------------ run
work=$(mktemp -d)
app_pid=""
dir=$REPO

stop_app() {
  [ -n "$app_pid" ] || return 0
  kill -TERM -- "-$app_pid" 2>/dev/null || kill -TERM "$app_pid" 2>/dev/null
  local i
  for i in $(seq 1 30); do curl -fsS -o /dev/null "$HEALTH_URL" 2>/dev/null || break; sleep 1; done
  # bootRun forks the app JVM; if it outlived Gradle, stop whatever still listens on 8080 from this run.
  if curl -fsS -o /dev/null "$HEALTH_URL" 2>/dev/null && command -v fuser >/dev/null; then fuser -k -TERM 8080/tcp >/dev/null 2>&1; fi
  app_pid=""
}
cleanup() {
  stop_app
  if [ "$MODE" = clone ] && [ -d "$work/rag-gateway" ]; then
    (cd "$work/rag-gateway" && docker compose --profile mock down -v >/dev/null 2>&1)
  fi
  rm -rf "$work"
}
trap cleanup EXIT

if curl -fsS -o /dev/null "$HEALTH_URL" 2>/dev/null; then
  echo "FAIL  something already answers on $HEALTH_URL; stop the running gateway first"
  exit 1
fi

if [ "$MODE" = clone ]; then
  dir="$work/rag-gateway"
  export COMPOSE_PROJECT_NAME=rag-gateway-quickstart
  git clone -q "$REPO" "$dir" && git -C "$dir" checkout -q "$(git rev-parse HEAD)" \
    || { echo "FAIL  could not clone $REPO"; exit 1; }
  echo "== clean clone of $(git rev-parse --short HEAD) in $dir"
else
  echo "== working tree $dir"
fi

cd "$dir" || exit 1
for i in $(seq 0 $((boot_index - 1))); do
  echo "\$ ${CMDS[$i]}"
  bash -c "${CMDS[$i]}" || { echo "FAIL  command $((i + 1)) exited non-zero"; exit 1; }
done

echo "\$ ${CMDS[$boot_index]}   (background, log: $work/bootRun.log)"
setsid bash -c "${CMDS[$boot_index]}" > "$work/bootRun.log" 2>&1 < /dev/null &
app_pid=$!
started=0
for _ in $(seq 1 "$START_TIMEOUT"); do
  if curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"UP"'; then started=1; break; fi
  kill -0 "$app_pid" 2>/dev/null || break
  sleep 1
done
if [ "$started" != 1 ]; then
  echo "FAIL  the app did not report UP within ${START_TIMEOUT}s; last log lines:"
  tail -20 "$work/bootRun.log" | sed 's/^/        /'
  exit 1
fi
echo "   app is UP"

# The remaining commands in one shell, so `export` carries over; the final curl also writes status and body.
script=""
for i in $(seq $((boot_index + 1)) $((last - 1))); do
  echo "\$ ${CMDS[$i]}"
  script+="${CMDS[$i]} || exit 90"$'\n'
done
echo "\$ ${CMDS[$last]}"
script+="${CMDS[$last]} -o '$work/body' -w '%{http_code}' > '$work/code' || exit 91"$'\n'
bash -c "set -o pipefail; $script"
rc=$?
code=$(cat "$work/code" 2>/dev/null || echo none)
echo "   HTTP $code: $(head -c 300 "$work/body" 2>/dev/null)"
echo

if [ "$rc" -eq 0 ] && [ "$code" = 200 ] && grep -q '"chat.completion"' "$work/body" 2>/dev/null; then
  echo "OK    AC-t08-9: the README quickstart gives a 200 chat completion"
  exit 0
fi
echo "FAIL  AC-t08-9: expected HTTP 200 with a chat.completion body, got $code (shell exit $rc)"
exit 1
