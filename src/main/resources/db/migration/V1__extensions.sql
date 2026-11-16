-- V1 (scaffold). Extensions only. Schema numbering is pre-assigned in docs/process/WORK_PLAN.md.
-- pgvector >= 0.8.0 is required for iterative index scans (t12); the compose image pins 0.8.7.
CREATE EXTENSION IF NOT EXISTS vector;
