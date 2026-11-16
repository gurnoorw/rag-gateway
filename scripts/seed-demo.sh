#!/usr/bin/env bash
# Seed the local database with demo tenants and API keys (t01, DECISIONS.md C16, D-22).
#
#   docker compose up -d && ./gradlew bootRun     # Flyway creates the tables on first start
#   scripts/seed-demo.sh                          # in another shell
#
# Creates two tenants and three keys (one admin) with different ACL groups. Each key is 32 random bytes,
# base64url, prefixed rgw_. Only the SHA-256 hex goes into the database; the plaintext keys are printed
# once, here, and cannot be recovered later. Running the script again revokes the previous demo keys and
# prints new ones; tenants are upserted.
#
# Database access: by default psql inside the compose postgres service. Set PSQL to use another client,
# e.g. PSQL="psql postgresql://raggateway:raggateway@localhost:5432/raggateway".
set -euo pipefail

cd "$(dirname "$0")/.."
DB_USER="${POSTGRES_USER:-raggateway}"
DB_NAME="${POSTGRES_DB:-raggateway}"
PSQL="${PSQL:-docker compose exec -T postgres psql -U ${DB_USER} -d ${DB_NAME}}"

for tool in openssl; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

new_key() {
  # 32 random bytes, base64url without padding
  printf 'rgw_%s' "$(openssl rand 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
}

sha256_hex() {
  printf '%s' "$1" | openssl dgst -sha256 -r | cut -d' ' -f1
}

ACME_ADMIN=$(new_key)
ACME_SUPPORT=$(new_key)
GLOBEX_DEV=$(new_key)

# Hashes are hex and names are fixed strings, so plain interpolation into SQL is safe here.
$PSQL -v ON_ERROR_STOP=1 -q <<SQL
BEGIN;
INSERT INTO tenants (id, name, tokens_per_minute, monthly_budget_usd, allowed_models) VALUES
  ('acme',   'Acme Corp',   100000, 50.00, '{}'),
  ('globex', 'Globex Inc',   20000,  5.00, '{claude-haiku-5-5,llama3.2:1b}')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, tokens_per_minute = EXCLUDED.tokens_per_minute,
  monthly_budget_usd = EXCLUDED.monthly_budget_usd, allowed_models = EXCLUDED.allowed_models;

UPDATE api_keys SET revoked_at = now()
WHERE name LIKE 'demo:%' AND tenant_id IN ('acme', 'globex') AND revoked_at IS NULL;

INSERT INTO api_keys (tenant_id, key_hash, name, groups, is_admin) VALUES
  ('acme',   '$(sha256_hex "$ACME_ADMIN")',   'demo:acme-admin',   '{eng,support,finance}', true),
  ('acme',   '$(sha256_hex "$ACME_SUPPORT")', 'demo:acme-support', '{support}',             false),
  ('globex', '$(sha256_hex "$GLOBEX_DEV")',   'demo:globex-dev',   '{eng}',                 false);
COMMIT;
SQL

cat <<EOF
Seeded tenants acme and globex. These keys are shown once; store them now.

  acme   admin   groups eng,support,finance   $ACME_ADMIN
  acme   support groups support               $ACME_SUPPORT
  globex dev     groups eng                   $GLOBEX_DEV

Example:
  curl -N http://localhost:8080/v1/chat/completions \\
    -H "Authorization: Bearer <one of the keys above>" -H 'Content-Type: application/json' \\
    -d '{"model":"claude-haiku-5-5","stream":true,"messages":[{"role":"user","content":"Say hi"}]}'
EOF
