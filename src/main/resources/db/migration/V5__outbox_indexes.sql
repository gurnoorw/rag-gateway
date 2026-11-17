-- V5 (t11 review, D-52): the reserved t11 migration. The outbox had no index on document_id, so deleting a
-- document (whose ON DELETE CASCADE removes its jobs), markDead's join and the dead-job gauge's "no newer job for
-- this document" check all scanned the whole outbox, which keeps every DONE row. The gauge, refreshed after every
-- poll, also reads DEAD rows through a small partial index instead of the table.
CREATE INDEX outbox_document ON outbox (document_id, created_at);
CREATE INDEX outbox_dead     ON outbox (created_at) WHERE status = 'DEAD';
