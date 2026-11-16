-- V2 (t01 round prep). Tenants and their API keys. Keys are stored only as SHA-256 hex (D-22);
-- ACL groups and the admin flag belong to the key, not the tenant (C16).
CREATE TABLE tenants (
  id                 text PRIMARY KEY,
  name               text NOT NULL,
  tokens_per_minute  bigint NOT NULL CHECK (tokens_per_minute > 0),
  monthly_budget_usd numeric(12,2) NOT NULL CHECK (monthly_budget_usd >= 0),
  allowed_models     text[] NOT NULL DEFAULT '{}',
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  text NOT NULL REFERENCES tenants(id),
  key_hash   char(64) NOT NULL UNIQUE,          -- SHA-256 hex, never the key
  name       text NOT NULL,
  groups     text[] NOT NULL DEFAULT '{}',
  is_admin   boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz
);
