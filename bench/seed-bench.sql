-- Benchmark tenants and keys (t08), run by bench/run-all.sh once the app has migrated the schema:
--   psql -v ON_ERROR_STOP=1 -v prefix=bench-20261116t2130z -v h_main=<sha256 hex> ... -f bench/seed-bench.sql
-- Every run gets its own tenants (prefix = "bench-" + the run's UTC start time), so the cache, the token bucket,
-- the budget and request_log rows of one run never mix with another's, and nothing is ever deleted (WORK_PLAN
-- t08: a tenant per run instead of resetting request_log). Keys are random per run; only their SHA-256 goes in.
--
-- Large tenants: TPM and budget far above what a run uses, so only the scenario under test can reject a request.
-- The "ratelimit" tenant has a small TPM (scenario 6). allowed_models '{}' = every catalog model.
BEGIN;
INSERT INTO tenants (id, name, tokens_per_minute, monthly_budget_usd, allowed_models) VALUES
  (:'prefix' || '-main',          'bench: latency, fallback, streams', 1000000000, 1000000.00, '{}'),
  (:'prefix' || '-cachehit',      'bench: cache-hit latency',          1000000000, 1000000.00, '{}'),
  (:'prefix' || '-hitrate',       'bench: cache hit rate',             1000000000, 1000000.00, '{}'),
  (:'prefix' || '-cost-baseline', 'bench: cost, baseline',             1000000000, 1000000.00, '{}'),
  (:'prefix' || '-cost-cache',    'bench: cost, cache only',           1000000000, 1000000.00, '{}'),
  (:'prefix' || '-cost-routing',  'bench: cost, routing only',         1000000000, 1000000.00, '{}'),
  (:'prefix' || '-cost-both',     'bench: cost, cache and routing',    1000000000, 1000000.00, '{}'),
  (:'prefix' || '-ratelimit',     'bench: rate limit',                 :ratelimit_tpm, 1000000.00, '{}');

INSERT INTO api_keys (tenant_id, key_hash, name) VALUES
  (:'prefix' || '-main',          :'h_main',          'bench:main'),
  (:'prefix' || '-cachehit',      :'h_cachehit',      'bench:cachehit'),
  (:'prefix' || '-hitrate',       :'h_hitrate',       'bench:hitrate'),
  (:'prefix' || '-cost-baseline', :'h_cost_baseline', 'bench:cost-baseline'),
  (:'prefix' || '-cost-cache',    :'h_cost_cache',    'bench:cost-cache'),
  (:'prefix' || '-cost-routing',  :'h_cost_routing',  'bench:cost-routing'),
  (:'prefix' || '-cost-both',     :'h_cost_both',     'bench:cost-both'),
  (:'prefix' || '-ratelimit',     :'h_ratelimit',     'bench:ratelimit');
COMMIT;
