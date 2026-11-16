#!/usr/bin/env bash
# t08 benchmark: every scenario of docs/process/WORK_PLAN.md (t08) in one run, one committed summary per run
# (D-45). From a clean checkout:
#
#   bash bench/run-all.sh --measured-on "cloud container, 4 vCPU / 15 GB"
#   bash bench/run-all.sh --measured-on "8 GB MacBook Air"
#   bash bench/run-all.sh --measured-on "<label>" --only 1,8     # a subset (6b and 8 need 1, 7b needs 7)
#
# --measured-on is required and has no default, so no run can carry the wrong hardware label (C14).
# --allow-dirty runs uncommitted code but writes the summary to bench/results/ (gitignored), not bench/summary/:
# a committed summary always names a commit that holds the code that produced it.
#
# Steps: build the jar; start Postgres, Redis and the WireMock mocks (compose profile "mock"); start the app as
# on the laptop (-Xmx512m, virtual threads, Spring profile "bench"); seed fresh tenants for this run
# (bench/seed-bench.sql); run each scenario with k6 from the pinned image; read /actuator/prometheus and
# request_log; write bench/summary/<run_id>.json and validate it against bench/summary.schema.json
# (bench/lib/summary.py). Raw k6 output and logs stay in bench/results/ (gitignored).
#
# Needs docker with compose, java 21 (java, jcmd), python3, openssl, curl, git; runs on bash 3.2 (macOS).
# Ports: 8080 (app) must be free; 8081/8082 (mocks), 5432, 6379 free or held by this project's compose services.
#
# Exits non-zero on any error and stops everything it started (the app, the mocks, Postgres/Redis unless they
# were already running), also on failure or Ctrl-C. It never calls a real provider: the bench profile points
# both providers at the mocks, with no environment override.
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1   # no __pycache__ under bench/lib (not gitignored)

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

MEASURED_ON=""
ONLY=""
ALLOW_DIRTY=false
while [ $# -gt 0 ]; do
  case "$1" in
    --measured-on) [ $# -ge 2 ] || usage; MEASURED_ON="$2"; shift 2 ;;
    --only) [ $# -ge 2 ] || usage; ONLY="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=true; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done
if [ "${#MEASURED_ON}" -lt 3 ]; then
  echo 'error: --measured-on "<hardware label>" is required, e.g. --measured-on "cloud container, 4 vCPU / 15 GB"' >&2
  exit 2
fi

cd "$(dirname "$0")/.."
ROOT="$PWD"

# ---------------------------------------------------------------- parameters (all go into the run manifest)
K6_IMAGE="grafana/k6:2.3.0"
XMX="512m"
PROM_URL="http://localhost:8080/actuator/prometheus"
OVERHEAD_RATE=100; OVERHEAD_DURATION=110s        # 11,000 requests per leg (AC-t08-5: >= 10,000)
WARMUP_RATE=20; WARMUP_DURATION=20s              # JIT warm-up through the gateway before scenario 1, not reported
CACHE_RATE=50; CACHE_DURATION=40s; CACHE_POOL=20
HITRATE_RATE=50; HITRATE_DURATION=20s; HITRATE_POOL=20
COST_RATE=50; COST_DURATION=21s; COST_REQUESTS=1000
RATELIMIT_TPM=20000; RATELIMIT_RATE=10; RATELIMIT_DURATION_S=120; RATELIMIT_WARM_S=60
FALLBACK_RATE=20; FALLBACK_DURATION=60s; FALLBACK_KILL_AFTER_S=25
REDIS_DOWN_DURATION=30s
STREAM_NS="500 1000 2000 4000"; STREAM_HOLD=20s; STREAM_SECONDS=5.1
DELAYED_N=1000; DELAYED_HOLD=30s
SETTLE_S=10                                      # between open-streams steps, so one step's tail does not overlap the next
MOCK_LATENCY="WireMock chunked dribble: first chunk (holding the first token) after 300 ms, then 10 more chunks every 100 ms, last at 1.3 s; scenario 7 uses a 5.1 s stream (first chunk at 300 ms, 25 chunks every 200 ms); scenario 7b holds the Claude response headers for 20 s"
ALL_SCENARIOS="1 2 3 4 5 6 6b 7 7b 8"

# ---------------------------------------------------------------- which scenarios
SELECTED=" "
if [ -z "$ONLY" ]; then
  SELECTED=" $ALL_SCENARIOS "
else
  for s in $(echo "$ONLY" | tr ',' ' '); do
    case " $ALL_SCENARIOS " in
      *" $s "*) SELECTED="$SELECTED$s " ;;
      *) echo "error: unknown scenario '$s' (known: $ALL_SCENARIOS)" >&2; exit 2 ;;
    esac
  done
  # 6b and 8 are computed against scenario 1; 7b's block sits inside scenario 7's.
  case "$SELECTED" in *" 6b "*|*" 8 "*) SELECTED="${SELECTED}1 " ;; esac
  case "$SELECTED" in *" 7b "*) SELECTED="${SELECTED}7 " ;; esac
fi
runs() { case "$SELECTED" in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# ---------------------------------------------------------------- preconditions
for tool in docker java jcmd python3 openssl curl git; do
  command -v "$tool" >/dev/null || { echo "error: missing tool: $tool" >&2; exit 1; }
done
docker compose version >/dev/null 2>&1 || { echo "error: docker compose is required" >&2; exit 1; }

DIRTY=$(git status --porcelain --untracked-files=no)
if [ -n "$DIRTY" ] && [ "$ALLOW_DIRTY" != true ]; then
  echo "error: uncommitted changes. Commit first (a summary names the commit that produced it), or pass --allow-dirty for a run that is not committed." >&2
  exit 1
fi

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
if port_in_use 8080; then
  echo "error: port 8080 is in use. Stop the running app first (its load would also distort the numbers)." >&2
  exit 1
fi

PRICE_DATE=$(grep -oE 'list prices \(USD per million tokens\) taken on [0-9]{4}-[0-9]{2}-[0-9]{2}' \
  src/main/resources/application.yml | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1 || true)
[ -n "$PRICE_DATE" ] || { echo "error: no price date in application.yml (the gateway.models comment)" >&2; exit 1; }
if grep -A2 '^  threads:' src/main/resources/application.yml | grep -q 'enabled: true'; then VIRTUAL=true; else VIRTUAL=false; fi

RUN_TS=$(date -u +%Y%m%dT%H%MZ)
SLUG=$(printf '%s' "$MEASURED_ON" | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//')
RUN_ID="$RUN_TS-$SLUG"
PREFIX="bench-$(printf '%s' "$RUN_TS" | tr 'A-Z' 'a-z')"
RESULTS="bench/results"
if [ "$ALLOW_DIRTY" = true ] && [ -n "$DIRTY" ]; then OUT="$RESULTS/$RUN_ID-summary.json"; else OUT="bench/summary/$RUN_ID.json"; fi
mkdir -p "$RESULTS" bench/summary
[ ! -e "$OUT" ] || { echo "error: $OUT already exists" >&2; exit 1; }
R="$RESULTS/$RUN_ID"          # prefix of every raw file of this run
NOTES_FILE=$(mktemp)
NOT_RUN_FILE=$(mktemp)

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
note() { printf '%s\n' "$*" >>"$NOTES_FILE"; }

# ---------------------------------------------------------------- helpers
APP_PID=""
APP_LOG=""
SAMPLER_STOP=""
STARTED_SERVICES=""
RUNNING_BEFORE=$(docker compose --profile mock ps --services --status running 2>/dev/null || true)
was_running() { printf '%s\n' "$RUNNING_BEFORE" | grep -qx "$1"; }
is_running() { docker compose --profile mock ps --services --status running 2>/dev/null | grep -qx "$1"; }

wait_for() {   # wait_for <seconds> <what> <command...>
  local limit=$1 what=$2 i=0; shift 2
  until "$@" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ $i -ge $((limit * 2)) ]; then echo "error: timed out waiting for $what" >&2; return 1; fi
    sleep 0.5
  done
}
mock_healthy() { curl -sf "http://localhost:$1/__admin/health"; }
redis_up() { docker compose exec -T redis redis-cli ping; }

start_services() {
  local svc
  for svc in postgres redis mock-claude mock-ollama; do
    was_running "$svc" || STARTED_SERVICES="$STARTED_SERVICES $svc"
  done
  log "starting compose services (profile mock)"
  docker compose --profile mock up -d postgres redis mock-claude mock-ollama >/dev/null
  wait_for 60 postgres docker compose exec -T postgres pg_isready -U "${POSTGRES_USER:-raggateway}"
  wait_for 60 redis redis_up
  wait_for 60 mock-claude mock_healthy 8081
  wait_for 60 mock-ollama mock_healthy 8082
}

APP_JAR=""
start_app() {   # start_app <log-name> [VAR=value ...] [-- java options...]
  local name=$1; shift
  local envs="" opts=""
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do envs="$envs $1"; shift; done
  [ $# -gt 0 ] && shift
  opts="$*"
  APP_LOG="$R-app-$name.log"
  # shellcheck disable=SC2086  # envs and opts are word lists built above, without spaces inside a word
  env $envs java "-Xmx$XMX" -Dspring.profiles.active=bench $opts -jar "$APP_JAR" >"$APP_LOG" 2>&1 &
  APP_PID=$!
  local i=0
  until curl -sf "$PROM_URL" >/dev/null 2>&1; do
    if ! kill -0 "$APP_PID" 2>/dev/null; then APP_PID=""; echo "error: the app exited during startup, see $APP_LOG" >&2; return 1; fi
    i=$((i + 1)); [ $i -lt 240 ] || { echo "error: the app did not start within 120 s, see $APP_LOG" >&2; return 1; }
    sleep 0.5
  done
  log "app up (pid $APP_PID${envs:+, env$envs}${opts:+, $opts}), log $APP_LOG"
}

stop_app() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    local i=0
    while kill -0 "$APP_PID" 2>/dev/null && [ $i -lt 60 ]; do sleep 0.5; i=$((i + 1)); done
    kill -9 "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  APP_PID=""
}

cleanup() {
  local rc=$?
  set +e
  [ -n "$SAMPLER_STOP" ] && touch "$SAMPLER_STOP"
  stop_app
  # A service this run stopped on purpose (mock-claude in 5, redis in 6b) that was running before: start it again.
  local svc
  for svc in postgres redis mock-claude mock-ollama; do
    if was_running "$svc" && ! is_running "$svc"; then docker compose --profile mock start "$svc" >/dev/null 2>&1; fi
  done
  if [ -n "$STARTED_SERVICES" ]; then
    log "stopping$STARTED_SERVICES"
    # shellcheck disable=SC2086
    docker compose --profile mock stop $STARTED_SERVICES >/dev/null 2>&1
  fi
  rm -f "$NOTES_FILE" "$NOT_RUN_FILE"
  [ $rc -eq 0 ] || log "FAILED (exit $rc); raw output in $R-*"
  exit $rc
}
trap cleanup EXIT
trap 'exit 130' INT TERM

k6() {   # k6 <result-name> <script> [VAR=value ...]; the key travels in the environment (KEY), never in argv
  local name=$1 script=$2; shift 2
  local envs="" kv
  for kv in "$@"; do envs="$envs -e $kv"; done
  log "k6 $script $* -> $R-$name.json"
  local rc=0
  # shellcheck disable=SC2086
  docker run --rm --network host -u "$(id -u):$(id -g)" -v "$ROOT/bench:/bench" -e KEY $envs "$K6_IMAGE" \
    run --quiet --summary-export "/bench/results/$RUN_ID-$name.json" "/bench/k6/$script" >"$R-$name.log" 2>&1 || rc=$?
  if [ $rc -ne 0 ]; then
    echo "error: k6 $script exited $rc (99 = an error threshold failed); see $R-$name.log" >&2
    tail -30 "$R-$name.log" >&2
    return 1
  fi
  [ -s "$R-$name.json" ] || { echo "error: k6 wrote no summary for $name" >&2; return 1; }
}

snapshot() { python3 bench/lib/prom.py snapshot "$PROM_URL" "$R-$1.json"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
file_size() { wc -c <"$1" | tr -d ' '; }

new_key() { printf 'rgw_%s' "$(openssl rand 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')"; }
sha256_hex() { printf '%s' "$1" | openssl dgst -sha256 -r | cut -d' ' -f1; }

TENANTS="main cachehit hitrate cost-baseline cost-cache cost-routing cost-both ratelimit"
key_var() { printf 'BENCH_KEY_%s' "$(printf '%s' "$1" | tr 'a-z-' 'A-Z_')"; }
use_key() { local v; v=$(key_var "$1"); KEY="${!v}"; export KEY; }
seed() {
  local args="" t v k
  for t in $TENANTS; do
    k=$(new_key); v=$(key_var "$t")
    printf -v "$v" '%s' "$k"
    args="$args -v h_$(printf '%s' "$t" | tr '-' '_')=$(sha256_hex "$k")"
  done
  # shellcheck disable=SC2086
  docker compose exec -T postgres psql -U "${POSTGRES_USER:-raggateway}" -d "${POSTGRES_DB:-raggateway}" \
    -v ON_ERROR_STOP=1 -q -v "prefix=$PREFIX" -v "ratelimit_tpm=$RATELIMIT_TPM" $args <bench/seed-bench.sql
  log "seeded tenants $PREFIX-*"
}

app_answers() { kill -0 "$APP_PID" 2>/dev/null && curl -sf --max-time 10 "$PROM_URL" >/dev/null 2>&1; }

# sampled_k6 <result-name> <script> [VAR=value ...]: k6 plus heap/thread sampling and the app-log slice of the
# step. An app that no longer answers afterwards (e.g. out of heap) is a result, not a harness error: the step
# is marked, the app restarted with a fresh log, and the next step runs on it.
sampled_k6() {
  local name=$1 start sampler rc=0 lfile restarted=false
  lfile="$APP_LOG"
  start=$(file_size "$lfile")
  SAMPLER_STOP="$R-$name-sampler.stop"
  rm -f "$SAMPLER_STOP"
  python3 bench/lib/sampler.py "$PROM_URL" "$APP_PID" "$SAMPLER_STOP" "$R-$name-sample.json" &
  sampler=$!
  k6 "$@" || rc=$?
  touch "$SAMPLER_STOP"
  wait "$sampler" || rc=1
  rm -f "$SAMPLER_STOP"; SAMPLER_STOP=""
  local end; end=$(file_size "$lfile")
  if ! app_answers; then
    log "the app no longer answers after $name; restarting it"
    restarted=true
    stop_app
    start_app "streams-after-$name" -- -Djdk.tracePinnedThreads=short
  fi
  printf '{"log": "%s", "start": %s, "end": %s, "xmx": "%s", "app_restarted": %s}\n' \
    "$lfile" "$start" "$end" "$XMX" "$restarted" >"$R-$name-log.json"
  return $rc
}

blackhole_probe() {   # prints the first candidate whose TCP connect neither connects nor is refused within 1 s
  local bridge
  bridge=$(docker network inspect bridge --format '{{(index .IPAM.Config 0).Subnet}}' 2>/dev/null \
    | sed -E 's#^([0-9]+\.[0-9]+)\..*#\1.255.254#' || true)
  # shellcheck disable=SC2086
  python3 - "$R-6b-blackhole-probe.json" ${BENCH_BLACKHOLE_HOST:-} 10.255.255.1 192.0.2.1 $bridge <<'PY'
import json, socket, sys
out, candidates = sys.argv[1], sys.argv[2:]
tried = []
for host in candidates:
    s = socket.socket()
    s.settimeout(1.0)
    try:
        s.connect((host, 6379))
        tried.append(f"{host}: connected")
    except socket.timeout:
        json.dump({"host": host, "timeout_s": 1.0, "tried": tried}, open(out, "w"))
        print(host)
        sys.exit(0)
    except OSError as e:
        tried.append(f"{host}: {e.strerror or e}")
    finally:
        s.close()
json.dump({"host": None, "tried": tried}, open(out, "w"))
print("; ".join(tried), file=sys.stderr)
sys.exit(1)
PY
}

# ---------------------------------------------------------------- build, services, app, seed
log "run $RUN_ID (commit $(git rev-parse --short=12 HEAD)), scenarios:$SELECTED"
log "building the jar"
./gradlew --quiet bootJar
APP_JAR=$(ls build/libs/*.jar | grep -v -- '-plain\.jar$' | head -1)
start_services
start_app main
seed

# ---------------------------------------------------------------- 1 and 8: overhead and log-insert cost
if runs 1; then
  use_key main
  k6 1-warmup 1-gateway.js "RATE=$WARMUP_RATE" "DURATION=$WARMUP_DURATION" TAG=warmup
  k6 1-direct 1-direct.js "RATE=$OVERHEAD_RATE" "DURATION=$OVERHEAD_DURATION"
  snapshot prom-1-before
  k6 1-gateway 1-gateway.js "RATE=$OVERHEAD_RATE" "DURATION=$OVERHEAD_DURATION" TAG=s1
  snapshot prom-1-after
  note "scenario 1: a $WARMUP_DURATION gateway warm-up at $WARMUP_RATE/s ran first and is not reported"
fi

# ---------------------------------------------------------------- 2: cache-hit latency
if runs 2; then
  use_key cachehit
  k6 2 2.js "RATE=$CACHE_RATE" "DURATION=$CACHE_DURATION" "POOL=$CACHE_POOL"
fi

# ---------------------------------------------------------------- 3: cache hit rate
if runs 3; then
  use_key hitrate
  k6 3 3.js "RATE=$HITRATE_RATE" "DURATION=$HITRATE_DURATION" "POOL=$HITRATE_POOL"
fi

# ---------------------------------------------------------------- 4: cost per 1,000 requests, one tenant per run
if runs 4; then
  for v in baseline cache routing both; do
    use_key "cost-$v"
    k6 "4-$v" 4.js "VARIANT=$v" "RATE=$COST_RATE" "DURATION=$COST_DURATION" "REQUESTS=$COST_REQUESTS"
  done
fi

# ---------------------------------------------------------------- 6: rate limit
if runs 6; then
  use_key ratelimit
  k6 6 6.js "RATE=$RATELIMIT_RATE" "DURATION=${RATELIMIT_DURATION_S}s"
fi

# ---------------------------------------------------------------- 5: fallback (kills mock-claude, so last on this app)
if runs 5; then
  use_key main
  KILL_AT_MS=$(( $(now_ms) + FALLBACK_KILL_AFTER_S * 1000 ))
  k6 5 5.js "RATE=$FALLBACK_RATE" "DURATION=$FALLBACK_DURATION" "KILL_EPOCH_MS=$KILL_AT_MS" &
  K6_PID=$!
  python3 -c "import time; time.sleep(max(0.0, $KILL_AT_MS / 1000 - time.time()))"
  kill_start=$(now_ms)
  docker compose --profile mock kill mock-claude >/dev/null 2>&1
  kill_done=$(now_ms)
  log "mock-claude killed"
  printf '{"kill_epoch_ms": %s, "kill_done_epoch_ms": %s}\n' "$kill_start" "$kill_done" >"$R-5-kill.json"
  wait "$K6_PID"
  docker compose --profile mock start mock-claude >/dev/null
  wait_for 60 mock-claude mock_healthy 8081
fi
stop_app

# ---------------------------------------------------------------- 6b: Redis unavailable from start
if runs 6b; then
  use_key main
  if BLACKHOLE=$(blackhole_probe); then
    docker compose stop redis >/dev/null
    start_app redis-refused
    k6 6b-refused 6b.js "RATE=$OVERHEAD_RATE" "DURATION=$REDIS_DOWN_DURATION" TAG=6b-refused FAIL_ON_ERRORS=false
    stop_app
    start_app redis-blackholed "REDIS_HOST=$BLACKHOLE"
    k6 6b-blackholed 6b.js "RATE=$OVERHEAD_RATE" "DURATION=$REDIS_DOWN_DURATION" TAG=6b-blackholed FAIL_ON_ERRORS=false
    stop_app
    docker compose start redis >/dev/null
    wait_for 60 redis redis_up
  else
    printf '6b\tno candidate address black-holes a TCP connect on this host (each one was refused or answered; see %s); set BENCH_BLACKHOLE_HOST to one that does\n' \
      "$R-6b-blackhole-probe.json" >>"$NOT_RUN_FILE"
    log "6b not run: no black-holed address found"
  fi
fi

# ---------------------------------------------------------------- 7 and 7b: open streams, headers delayed
if runs 7; then
  use_key main
  start_app streams -- -Djdk.tracePinnedThreads=short
  for n in $STREAM_NS; do
    sampled_k6 "7-n$n" 7.js "N=$n" "HOLD=$STREAM_HOLD"
    sleep "$SETTLE_S"
  done
  if runs 7b; then
    sampled_k6 7b 7b.js "N=$DELAYED_N" "HOLD=$DELAYED_HOLD"
  fi
  stop_app
fi

# ---------------------------------------------------------------- manifest and summary
export BENCH_MANIFEST="$R-run.json" RUN_ID MEASURED_ON PREFIX PRICE_DATE VIRTUAL K6_IMAGE XMX MOCK_LATENCY SELECTED \
  NOTES_FILE NOT_RUN_FILE OVERHEAD_RATE OVERHEAD_DURATION CACHE_RATE CACHE_POOL HITRATE_RATE \
  HITRATE_DURATION HITRATE_POOL COST_RATE COST_REQUESTS FALLBACK_RATE FALLBACK_DURATION RATELIMIT_TPM \
  RATELIMIT_RATE RATELIMIT_DURATION_S RATELIMIT_WARM_S REDIS_DOWN_DURATION STREAM_NS STREAM_HOLD STREAM_SECONDS \
  DELAYED_N DELAYED_HOLD TENANTS
export RUN_DATE="${RUN_TS:0:4}-${RUN_TS:4:2}-${RUN_TS:6:2}" GIT_COMMIT
GIT_COMMIT=$(git rev-parse --short=12 HEAD)
python3 - <<'PY'
import json, os
e = os.environ
not_run = dict(line.split("\t", 1) for line in open(e["NOT_RUN_FILE"]).read().splitlines() if line)
manifest = {
    "run_id": e["RUN_ID"], "measured_on": e["MEASURED_ON"], "date": e["RUN_DATE"], "git_commit": e["GIT_COMMIT"],
    "k6_image": e["K6_IMAGE"], "price_date": e["PRICE_DATE"], "virtual_threads": e["VIRTUAL"] == "true",
    "mock_latency": e["MOCK_LATENCY"],
    "scenarios": [s for s in e["SELECTED"].split() if s not in not_run],
    "not_run": not_run,
    "notes": [n for n in open(e["NOTES_FILE"]).read().splitlines() if n],
    "tenants": {t: f"{e['PREFIX']}-{t}" for t in e["TENANTS"].split()},
    "params": {
        "xmx": e["XMX"], "overhead_rate": int(e["OVERHEAD_RATE"]), "overhead_duration": e["OVERHEAD_DURATION"],
        "cache_rate": int(e["CACHE_RATE"]), "cache_pool": int(e["CACHE_POOL"]),
        "hitrate_repeat_share": 0.5,
        "hitrate_workload": f"{e['HITRATE_RATE']}/s for {e['HITRATE_DURATION']}, temperature 0; even arrivals repeat "
                            f"one of {e['HITRATE_POOL']} pool prompts (cold at the start), odd arrivals are new prompts",
        "cost_workload": f"{e['COST_REQUESTS']} requests per run at {e['COST_RATE']}/s; 50 % repeats from a pool of 20 "
                         "prompts; 20 % long prompts (3,000 input tokens), 80 % short (100); 250 output tokens each; "
                         "baseline = claude-opus-5-5 at temperature 0.7, cache = temperature 0, routing = model auto",
        "fallback_rate": int(e["FALLBACK_RATE"]), "fallback_duration": e["FALLBACK_DURATION"],
        "ratelimit_tpm": int(e["RATELIMIT_TPM"]), "ratelimit_rate": int(e["RATELIMIT_RATE"]),
        "ratelimit_duration_s": int(e["RATELIMIT_DURATION_S"]), "ratelimit_warm_s": int(e["RATELIMIT_WARM_S"]),
        "redis_down_duration": e["REDIS_DOWN_DURATION"],
        "stream_ns": [int(n) for n in e["STREAM_NS"].split()], "stream_hold": e["STREAM_HOLD"],
        "stream_seconds": float(e["STREAM_SECONDS"]),
        "delayed_n": int(e["DELAYED_N"]), "delayed_hold": e["DELAYED_HOLD"],
    },
}
with open(e["BENCH_MANIFEST"], "w") as f:
    json.dump(manifest, f, indent=1)
PY
python3 bench/lib/summary.py "$RESULTS" "$RUN_ID" "$OUT"
log "done: $OUT"
