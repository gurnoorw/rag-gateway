#!/usr/bin/env bash
# rag-gateway: observability stack checks (t04).
#   AC-t04-4  `docker compose config --services`: 2 services by default, 4 with --profile obs
#   AC-t04-5  Prometheus targets API shows the gateway scrape target as up
#   plus      Grafana is healthy and has provisioned the rag-gateway dashboard
#
# Run from anywhere, with the obs profile up and the app running on the host:
#   docker compose --profile obs up -d && ./gradlew bootRun     # in another terminal
#   bash scripts/check_obs.sh
# Only AC-t04-4 (no containers needed):  bash scripts/check_obs.sh --config-only
#
# Env: PROM_URL (default http://localhost:9090), GRAFANA_URL (default http://localhost:3000),
#      WAIT_SECONDS (default 30): how long to wait for the target to turn up (scrape interval is 5 s).
set -u
cd "$(dirname "$0")/.." || exit 1

PROM_URL=${PROM_URL:-http://localhost:9090}
GRAFANA_URL=${GRAFANA_URL:-http://localhost:3000}
WAIT_SECONDS=${WAIT_SECONDS:-30}
failures=0

ok()   { printf "OK    %s\n" "$1"; }
fail() { printf "FAIL  %s\n" "$1"; failures=$((failures + 1)); }

echo "== AC-t04-4: compose services"
default_services=$(docker compose config --services | sort | tr '\n' ' ')
obs_services=$(docker compose --profile obs config --services | sort | tr '\n' ' ')
[ "$default_services" = "postgres redis " ] \
  && ok "default: $default_services" || fail "default should be 'postgres redis', got: $default_services"
[ "$obs_services" = "grafana postgres prometheus redis " ] \
  && ok "--profile obs: $obs_services" || fail "--profile obs should add grafana and prometheus, got: $obs_services"

if [ "${1:-}" = "--config-only" ]; then
  [ "$failures" -eq 0 ] && exit 0 || exit 1
fi

echo "== AC-t04-5: Prometheus scrape target"
# Prints "<health> <scrapeUrl> <lastError>" for the rag-gateway job, one line per target.
target_health() {
  curl -fsS "$PROM_URL/api/v1/targets?state=active" 2>/dev/null | python3 -c '
import json, sys
for t in json.load(sys.stdin)["data"]["activeTargets"]:
    if t["labels"].get("job") == "rag-gateway":
        print(t["health"], t["scrapeUrl"], t.get("lastError") or "-")'
}
health=""
deadline=$((SECONDS + WAIT_SECONDS))
while [ "$SECONDS" -lt "$deadline" ]; do
  health=$(target_health)
  case "$health" in up\ *) break ;; esac
  sleep 2
done
case "$health" in
  up\ *) ok "target $health" ;;
  "")    fail "no rag-gateway target at $PROM_URL (is the obs profile up?)" ;;
  *)     fail "target not up after ${WAIT_SECONDS}s: $health (is the app running on the host, port 8080?)" ;;
esac

echo "== Grafana provisioning"
if curl -fsS "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
  ok "Grafana healthy at $GRAFANA_URL"
  title=$(curl -fsS "$GRAFANA_URL/api/dashboards/uid/rag-gateway" 2>/dev/null \
    | python3 -c 'import json,sys; d=json.load(sys.stdin)["dashboard"]; print(d["title"], len(d.get("panels", [])), "panels")' 2>/dev/null)
  [ -n "$title" ] && ok "dashboard provisioned: $title" || fail "dashboard uid rag-gateway not found in Grafana"
else
  fail "Grafana not reachable at $GRAFANA_URL"
fi

[ "$failures" -eq 0 ] && { echo "all checks passed"; exit 0; } || { echo "$failures check(s) failed"; exit 1; }
