#!/usr/bin/env bash
# Seed the gateway key the answer eval uses (t13 `answers collect`, t14; DECISIONS.md D-53, D-55).
#
#   docker compose up -d && ./gradlew bootRun     # Flyway creates the tables on first start
#   scripts/seed-eval.sh                          # in another shell; prints the key once
#
# Creates (or updates) tenant `eval`, the tenant whose collections k8s-c200/400/800 the retrieval eval builds,
# and one non-admin key `eval:collect` with groups eng, support, finance: every group of the demo corpus, so
# answerable questions are asked with full visibility (ACL questions belong to the ACL eval, not to this key).
# The key is 32 random bytes, base64url, prefixed rgw_; only its SHA-256 hex goes into the database. It is
# printed once and cannot be recovered. Running the script again revokes the previous eval key.
#
# Store it as EVAL_GATEWAY_API_KEY in .env (gitignored). Never commit it.
#
# Budget: answering ~33 questions with top-5 sources is roughly 33 x 3K tokens; the limits below leave room for
# a re-run. The answers go through the normal pipeline, so they are charged to this tenant like any request.
#
# Database access: by default psql inside the compose postgres service. Set PSQL to use another client,
# e.g. PSQL="psql postgresql://raggateway:raggateway@localhost:5432/raggateway".
set -euo pipefail

cd "$(dirname "$0")/.."
DB_USER="${POSTGRES_USER:-raggateway}"
DB_NAME="${POSTGRES_DB:-raggateway}"
PSQL="${PSQL:-docker compose exec -T postgres psql -U ${DB_USER} -d ${DB_NAME}}"

command -v openssl >/dev/null || { echo "missing: openssl" >&2; exit 1; }

new_key() {
  printf 'rgw_%s' "$(openssl rand 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
}

sha256_hex() {
  printf '%s' "$1" | openssl dgst -sha256 -r | cut -d' ' -f1
}

EVAL_KEY=$(new_key)

# The hash is hex and every other value is a fixed string, so plain interpolation into SQL is safe here.
$PSQL -v ON_ERROR_STOP=1 -q <<SQL
BEGIN;
INSERT INTO tenants (id, name, tokens_per_minute, monthly_budget_usd, allowed_models) VALUES
  ('eval', 'RAG eval', 200000, 5.00, '{}')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, tokens_per_minute = EXCLUDED.tokens_per_minute,
  monthly_budget_usd = EXCLUDED.monthly_budget_usd, allowed_models = EXCLUDED.allowed_models;

UPDATE api_keys SET revoked_at = now()
WHERE name = 'eval:collect' AND tenant_id = 'eval' AND revoked_at IS NULL;

INSERT INTO api_keys (tenant_id, key_hash, name, groups, is_admin) VALUES
  ('eval', '$(sha256_hex "$EVAL_KEY")', 'eval:collect', '{eng,support,finance}', false);
COMMIT;
SQL

cat <<EOF
Seeded tenant eval. This key is shown once; store it now as EVAL_GATEWAY_API_KEY in .env.

  eval   collect groups eng,support,finance   $EVAL_KEY

Next: ./gradlew eval --args="answers collect --out build/eval/answers.jsonl"
EOF
