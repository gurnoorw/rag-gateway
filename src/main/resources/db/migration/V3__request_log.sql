-- V3 (t01 round prep). One row per request that reached the pipeline (C7).
-- No prompt or response text (G7). Unpartitioned, indexed by tenant and time (C9).
CREATE TABLE request_log (
  request_id      uuid PRIMARY KEY,
  tenant_id       text NOT NULL,
  api_key_id      uuid,
  requested_model text NOT NULL,
  model_used      text,
  provider_used   text,
  route_rule      text,
  fallback_used   boolean NOT NULL DEFAULT false,
  attempts        smallint NOT NULL DEFAULT 0,
  input_tokens    integer NOT NULL DEFAULT 0,
  output_tokens   integer NOT NULL DEFAULT 0,
  usage_estimated boolean NOT NULL DEFAULT false,
  cost_usd        numeric(12,6) NOT NULL DEFAULT 0,
  latency_ms      integer NOT NULL,
  ttft_ms         integer,
  stream          boolean NOT NULL,
  cache_status    text NOT NULL,
  status          text NOT NULL,                -- ok | error | client_cancelled
  error_code      text,
  http_status     smallint NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX request_log_tenant_time ON request_log (tenant_id, created_at);
