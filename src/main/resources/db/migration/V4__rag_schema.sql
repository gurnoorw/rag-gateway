-- V4 (t09 round prep): documents, chunks, outbox. DECISIONS.md C1 (vector(768)), C4 (outbox), C16/C17
-- (tenant and ACL groups copied onto every chunk), D-47 (t09 details). t11 adds no columns: the lease,
-- attempts and last_error columns are here already; V5 is reserved for an index t11 may need.

CREATE TABLE documents (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     text NOT NULL REFERENCES tenants(id),
  collection_id text NOT NULL,
  doc_id        text NOT NULL,
  title         text NOT NULL,
  content       text NOT NULL,
  acl_groups    text[] NOT NULL CHECK (cardinality(acl_groups) > 0),
  content_hash  char(64) NOT NULL,                 -- DocumentHash: title, content, sorted groups
  status        text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'indexed', 'failed')),
  chunk_count   integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, collection_id, doc_id)
);

CREATE TABLE chunks (
  chunk_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id   uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  tenant_id     text NOT NULL,
  collection_id text NOT NULL,
  doc_id        text NOT NULL,
  title         text NOT NULL,
  ordinal       integer NOT NULL CHECK (ordinal >= 0),
  text          text NOT NULL,
  tsv           tsvector GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || text)) STORED,
  embedding     vector(768) NOT NULL,
  acl_groups    text[] NOT NULL CHECK (cardinality(acl_groups) > 0),
  UNIQUE (document_id, ordinal)
);
-- Cosine distance (<=>): nomic-embed-text vectors are compared by cosine. HNSW with pgvector defaults
-- (m 16, ef_construction 64); ef_search and iterative scans are query-time settings (t10, t12).
CREATE INDEX chunks_embedding_hnsw ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunks_tsv_gin        ON chunks USING gin (tsv);
CREATE INDEX chunks_acl_gin        ON chunks USING gin (acl_groups);
CREATE INDEX chunks_tenant_coll    ON chunks (tenant_id, collection_id);

CREATE TABLE outbox (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id  uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  content_hash char(64) NOT NULL,                  -- the version this job indexes (stale-job skip, t11)
  status       text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'DEAD')),
  attempts     integer NOT NULL DEFAULT 0,
  lease_until  timestamptz,
  last_error   text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_claimable ON outbox (created_at) WHERE status IN ('PENDING', 'IN_PROGRESS');
