#!/usr/bin/env bash
# rag-gateway Gate 1 demo (t08): the gateway's main behaviours end to end, against the WireMock mock providers.
#
#   docker compose --profile mock up -d --wait
#   ./gradlew bootRun --args='--spring.profiles.active=bench'      # in another terminal
#   bash scripts/demo.sh
#
# Steps, each printing what it expects and what it got, and failing on a difference:
#   1. rate limit: concurrent requests over a small tenant's TPM -> 429 rate_limited with Retry-After
#   2. cache: the same temperature-0 request twice -> X-Gateway-Cache MISS, then HIT
#   3. fallback: mock-claude stopped -> 200 served by Ollama (X-Gateway-Provider: ollama)
#   4. output validation: a json_schema request -> 200 with content that matches the schema;
#      4b. an answer that does not match -> one retry, then 502 invalid_model_output
#   5. GET /v1/usage -> the demo tenant's requests, tokens and cost
#
# Self-contained: it creates its own two tenants and keys in Postgres (like scripts/seed-demo.sh; keys are
# never printed) and adds its own stubs to the mocks through the WireMock admin API, matched by a marker in
# the prompt and removed at the end, so it does not depend on the bench mappings' content. mock-claude is
# started again on exit even if a step fails.
#
# Env: GATEWAY_URL (default http://localhost:8080); COMPOSE (default "docker compose --profile mock");
#      MOCK_CLAUDE_URL / MOCK_OLLAMA_URL (default: the host port compose publishes for container port
#      MOCK_CONTAINER_PORT, default 8080); PSQL (default: psql inside the compose postgres service).
# Needs: docker compose, curl, python3, openssl. Exit code 0 when every step passes, 1 otherwise.
set -u
cd "$(dirname "$0")/.." || exit 1

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8080}
COMPOSE=${COMPOSE:-docker compose --profile mock}
MOCK_CONTAINER_PORT=${MOCK_CONTAINER_PORT:-8080}
DB_USER="${POSTGRES_USER:-raggateway}"
DB_NAME="${POSTGRES_DB:-raggateway}"
PSQL="${PSQL:-docker compose exec -T postgres psql -U ${DB_USER} -d ${DB_NAME}}"
TENANT=demo-gate1
SMALL_TENANT=demo-gate1-small
SMALL_TPM=1000
NONCE="$(date +%s)-$$"

failures=0
work=$(mktemp -d)
claude_stopped=0

for tool in curl python3 openssl docker; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

step()  { printf "\n== %s\n" "$1"; }
check() { # check <description> <expected> <actual>
  printf "   %-34s expected: %-40s actual: %s\n" "$1" "$2" "$3"
  if [ "$2" = "$3" ]; then printf "   PASS\n"; else printf "   FAIL\n"; failures=$((failures + 1)); fi
}
json() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {'json': json}, {'d': d}))" "$@" 2>/dev/null; }
header() { # header <file> <name>: value of the last such header, case-insensitive
  tr -d '\r' < "$1" | awk -v h="$(echo "$2" | tr 'A-Z' 'a-z')" -F': ' 'tolower($1) == h { v = $2 } END { print v }'
}

mock_url() { # mock_url <service> <override>
  if [ -n "$2" ]; then echo "$2"; return; fi
  local hp
  hp=$($COMPOSE port "$1" "$MOCK_CONTAINER_PORT" 2>/dev/null | tail -1)
  [ -n "$hp" ] && echo "http://localhost:${hp##*:}"
}

wait_for() { # wait_for <url> <seconds>
  local i
  for i in $(seq 1 "$2"); do curl -fsS -o /dev/null "$1" 2>/dev/null && return 0; sleep 1; done
  return 1
}

# ------------------------------------------------------------------ stubs (WireMock admin API)
STUB_IDS=()
add_stub() { # add_stub <mock url> <python expression building the mapping, given marker m>
  local id
  id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  python3 - "$id" "$2" > "$work/stub.json" <<'PY'
import json, sys
stub_id, kind = sys.argv[1], sys.argv[2]

def claude_sse(text, delay=0):
    ev = []
    def e(name, data): ev.append(f"event: {name}\ndata: {json.dumps(data)}\n\n")
    e("message_start", {"type": "message_start", "message": {"id": "msg_demo", "type": "message", "role": "assistant",
      "model": "claude-haiku-5-5", "content": [], "stop_reason": None, "stop_sequence": None,
      "usage": {"input_tokens": 12, "output_tokens": 1}}})
    e("content_block_start", {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}})
    e("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": text}})
    e("content_block_stop", {"type": "content_block_stop", "index": 0})
    e("message_delta", {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None},
      "usage": {"output_tokens": 6}})
    e("message_stop", {"type": "message_stop"})
    resp = {"status": 200, "headers": {"Content-Type": "text/event-stream"}, "body": "".join(ev)}
    if delay:
        resp["fixedDelayMilliseconds"] = delay
    return resp

def ollama_ndjson(text):
    lines = [{"model": "llama3.2:1b", "created_at": "2026-11-16T00:00:00Z",
              "message": {"role": "assistant", "content": text}, "done": False},
             {"model": "llama3.2:1b", "created_at": "2026-11-16T00:00:00Z",
              "message": {"role": "assistant", "content": ""}, "done": True, "done_reason": "stop",
              "prompt_eval_count": 12, "eval_count": 6}]
    return {"status": 200, "headers": {"Content-Type": "application/x-ndjson"},
            "body": "".join(json.dumps(l) + "\n" for l in lines)}

kinds = {
    "claude-slow":        ("/v1/messages", "rgw-demo-429",         claude_sse("Hello from the demo.", delay=2000)),
    "claude-cache":       ("/v1/messages", "rgw-demo-cache",       claude_sse("Seven.")),
    "claude-schema-ok":   ("/v1/messages", "rgw-demo-schema-ok",   claude_sse('{"city": "Paris"}')),
    "claude-schema-bad":  ("/v1/messages", "rgw-demo-schema-bad",  claude_sse("The capital is Paris.")),
    "ollama-fallback":    ("/api/chat",    "rgw-demo-fallback",    ollama_ndjson("Hello from the fallback.")),
}
path, marker, response = kinds[kind]
print(json.dumps({"id": stub_id, "priority": 1,
                  "request": {"method": "POST", "urlPath": path, "bodyPatterns": [{"contains": marker}]},
                  "response": response, "metadata": {"rag-gateway-demo": True}}))
PY
  if curl -fsS -o /dev/null -X POST "$1/__admin/mappings" -H 'Content-Type: application/json' -d @"$work/stub.json"; then
    STUB_IDS+=("$1 $id")
  else
    echo "   could not add stub $2 to $1" >&2
    failures=$((failures + 1))
  fi
}

count_requests() { # count_requests <mock url> <marker>: requests the mock received containing the marker
  curl -fsS -X POST "$1/__admin/requests/count" -H 'Content-Type: application/json' \
    -d "{\"method\":\"POST\",\"bodyPatterns\":[{\"contains\":\"$2\"}]}" 2>/dev/null \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])' 2>/dev/null
}

cleanup() {
  if [ "$claude_stopped" = 1 ]; then
    $COMPOSE start mock-claude >/dev/null 2>&1 || echo "could not restart mock-claude: run '$COMPOSE start mock-claude'" >&2
  fi
  local entry
  for entry in "${STUB_IDS[@]}"; do
    curl -fsS -o /dev/null -X DELETE "${entry% *}/__admin/mappings/${entry#* }" 2>/dev/null || true
  done
  rm -rf "$work"
}
trap cleanup EXIT

# ------------------------------------------------------------------ gateway calls
chat() { # chat <name> <key> <json body>: writes $work/<name>.{h,b,code}
  curl -sS -o "$work/$1.b" -D "$work/$1.h" -w '%{http_code}' "$GATEWAY_URL/v1/chat/completions" \
    -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -d "$3" > "$work/$1.code" 2>"$work/$1.err" \
    || echo 000 > "$work/$1.code"
}
body() { # body <marker text> <model> <temperature> [extra json fields]
  python3 -c 'import json,sys
d = {"model": sys.argv[2], "temperature": float(sys.argv[3]), "max_tokens": 64,
     "messages": [{"role": "user", "content": sys.argv[1]}]}
if len(sys.argv) > 4: d.update(json.loads(sys.argv[4]))
print(json.dumps(d))' "$@"
}

# ------------------------------------------------------------------ preflight
echo "== preflight"
wait_for "$GATEWAY_URL/actuator/health" 5 \
  || { echo "   gateway not reachable at $GATEWAY_URL; start it with the bench profile (see the header)"; exit 1; }
CLAUDE_URL=$(mock_url mock-claude "${MOCK_CLAUDE_URL:-}")
OLLAMA_URL=$(mock_url mock-ollama "${MOCK_OLLAMA_URL:-}")
[ -n "$CLAUDE_URL" ] && wait_for "$CLAUDE_URL/__admin/health" 5 \
  || { echo "   mock-claude admin API not reachable; run '$COMPOSE up -d --wait' or set MOCK_CLAUDE_URL"; exit 1; }
[ -n "$OLLAMA_URL" ] && wait_for "$OLLAMA_URL/__admin/health" 5 \
  || { echo "   mock-ollama admin API not reachable; run '$COMPOSE up -d --wait' or set MOCK_OLLAMA_URL"; exit 1; }
echo "   gateway $GATEWAY_URL, mock-claude $CLAUDE_URL, mock-ollama $OLLAMA_URL"

new_key() { printf 'rgw_%s' "$(openssl rand 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')"; }
sha256_hex() { printf '%s' "$1" | openssl dgst -sha256 -r | cut -d' ' -f1; }
KEY=$(new_key)
SMALL_KEY=$(new_key)
# Fixed names and hex hashes only, so plain interpolation into SQL is safe here.
$PSQL -v ON_ERROR_STOP=1 -q >/dev/null <<SQL || { echo "   could not seed the demo tenants"; exit 1; }
BEGIN;
INSERT INTO tenants (id, name, tokens_per_minute, monthly_budget_usd, allowed_models) VALUES
  ('$TENANT',       'Gate 1 demo',             100000, 10.00, '{}'),
  ('$SMALL_TENANT', 'Gate 1 demo, small TPM',  $SMALL_TPM, 10.00, '{}')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, tokens_per_minute = EXCLUDED.tokens_per_minute,
  monthly_budget_usd = EXCLUDED.monthly_budget_usd, allowed_models = EXCLUDED.allowed_models;
UPDATE api_keys SET revoked_at = now() WHERE name LIKE 'demo:gate1%' AND revoked_at IS NULL;
INSERT INTO api_keys (tenant_id, key_hash, name, groups, is_admin) VALUES
  ('$TENANT',       '$(sha256_hex "$KEY")',       'demo:gate1',       '{eng}', false),
  ('$SMALL_TENANT', '$(sha256_hex "$SMALL_KEY")', 'demo:gate1-small', '{eng}', false);
COMMIT;
SQL
echo "   tenants $TENANT and $SMALL_TENANT ($SMALL_TPM tokens/min) seeded with fresh keys (not printed)"

for kind in claude-slow claude-cache claude-schema-ok claude-schema-bad; do add_stub "$CLAUDE_URL" "$kind"; done
add_stub "$OLLAMA_URL" ollama-fallback

# ------------------------------------------------------------------ 1. rate limit
step "1. rate limit: 4 concurrent requests of ~420 reserved tokens each against a $SMALL_TPM tokens/min bucket"
b=$(body "rgw-demo-429 $NONCE" claude-haiku-5-5 0.7 '{"max_tokens": 400}')
for i in 1 2 3 4; do chat "rl$i" "$SMALL_KEY" "$b" & done
wait
n429=0; n200=0; retry_after=""; code429=""
for i in 1 2 3 4; do
  c=$(cat "$work/rl$i.code")
  [ "$c" = 200 ] && n200=$((n200 + 1))
  if [ "$c" = 429 ]; then
    n429=$((n429 + 1)); retry_after=$(header "$work/rl$i.h" Retry-After)
    code429=$(json "$work/rl$i.b" "d['error']['code']")
  fi
done
echo "   statuses: $(for i in 1 2 3 4; do printf '%s ' "$(cat "$work/rl$i.code")"; done)"
check "some requests admitted"         "yes"                 "$([ "$n200" -ge 1 ] && echo yes || echo "no ($n200 x 200)")"
check "some requests rejected"         "yes"                 "$([ "$n429" -ge 1 ] && echo yes || echo "no ($n429 x 429)")"
check "error code of the 429"          "rate_limited"        "${code429:-none}"
check "Retry-After is whole seconds"   "yes"                 "$(echo "$retry_after" | grep -Eq '^[1-9][0-9]*$' && echo yes || echo "no ('$retry_after')")"

# ------------------------------------------------------------------ 2. cache
step "2. cache: the same temperature-0 request twice"
b=$(body "rgw-demo-cache $NONCE: name a prime number" claude-haiku-5-5 0)
chat c1 "$KEY" "$b"
chat c2 "$KEY" "$b"
check "first: status"                  "200"                 "$(cat "$work/c1.code")"
check "first: X-Gateway-Cache"         "MISS"                "$(header "$work/c1.h" X-Gateway-Cache)"
check "second: status"                 "200"                 "$(cat "$work/c2.code")"
check "second: X-Gateway-Cache"        "HIT"                 "$(header "$work/c2.h" X-Gateway-Cache)"
check "second: same answer"            "$(json "$work/c1.b" "d['choices'][0]['message']['content']")" \
                                       "$(json "$work/c2.b" "d['choices'][0]['message']['content']")"
check "mock-claude calls for the key"  "1"                   "$(count_requests "$CLAUDE_URL" "rgw-demo-cache $NONCE")"

# ------------------------------------------------------------------ 3. fallback
step "3. fallback: mock-claude stopped"
claude_stopped=1
$COMPOSE stop mock-claude >/dev/null 2>&1 || echo "   could not stop mock-claude"
chat f1 "$KEY" "$(body "rgw-demo-fallback $NONCE" claude-haiku-5-5 0.7)"
check "status"                         "200"                 "$(cat "$work/f1.code")"
check "X-Gateway-Provider"             "ollama"              "$(header "$work/f1.h" X-Gateway-Provider)"
check "X-Gateway-Model"                "llama3.2:1b"         "$(header "$work/f1.h" X-Gateway-Model)"
check "answer"                         "Hello from the fallback." "$(json "$work/f1.b" "d['choices'][0]['message']['content']")"
$COMPOSE start mock-claude >/dev/null 2>&1 && claude_stopped=0
wait_for "$CLAUDE_URL/__admin/health" 30 || echo "   mock-claude did not come back"
# A restarted WireMock has lost the stubs added through its admin API.
for kind in claude-schema-ok claude-schema-bad; do add_stub "$CLAUDE_URL" "$kind"; done

# ------------------------------------------------------------------ 4. output validation
SCHEMA_FORMAT='{"response_format": {"type": "json_schema", "json_schema": {"name": "city", "schema": {"type": "object", "required": ["city"], "properties": {"city": {"type": "string"}}, "additionalProperties": false}}}}'
step "4. output validation: a json_schema request whose answer matches"
chat s1 "$KEY" "$(body "rgw-demo-schema-ok $NONCE: capital of France as JSON" claude-haiku-5-5 0.7 "$SCHEMA_FORMAT")"
check "status"                         "200"                 "$(cat "$work/s1.code")"
check "content matches the schema"     "Paris"               "$(json "$work/s1.b" "json.loads(d['choices'][0]['message']['content'])['city']" 2>/dev/null || true)"

step "4b. output validation: an answer that does not match the schema"
chat s2 "$KEY" "$(body "rgw-demo-schema-bad $NONCE: capital of France as JSON" claude-haiku-5-5 0.7 "$SCHEMA_FORMAT")"
check "status"                         "502"                 "$(cat "$work/s2.code")"
check "error code"                     "invalid_model_output" "$(json "$work/s2.b" "d['error']['code']")"
check "provider calls (one retry)"     "2"                   "$(count_requests "$CLAUDE_URL" "rgw-demo-schema-bad $NONCE")"

# ------------------------------------------------------------------ 5. usage
step "5. GET /v1/usage for $TENANT"
sent=5   # 2 cache + 1 fallback + 2 schema requests in this run; rows are written after each response closes
got=""
for _ in $(seq 1 10); do
  code=$(curl -sS -o "$work/u.b" -w '%{http_code}' "$GATEWAY_URL/v1/usage" -H "Authorization: Bearer $KEY")
  got=$(json "$work/u.b" "d['requests']")
  [ "$code" = 200 ] && [ -n "$got" ] && [ "$got" -ge "$sent" ] && break
  sleep 0.5
done
echo "   body: $(cat "$work/u.b")"
check "status"                         "200"                 "$code"
check "tenant"                         "$TENANT"             "$(json "$work/u.b" "d['tenant_id']")"
check "requests this month >= $sent"   "yes"                 "$([ -n "$got" ] && [ "$got" -ge "$sent" ] && echo yes || echo "no ($got)")"
check "cost_usd is a decimal string"   "yes"                 "$(json "$work/u.b" "d['cost_usd']" | grep -Eq '^[0-9]+\.[0-9]{6}$' && echo yes || echo no)"

echo
if [ "$failures" -eq 0 ]; then echo "Gate 1 demo: every step passed"; exit 0; fi
echo "Gate 1 demo: $failures check(s) failed"; exit 1
