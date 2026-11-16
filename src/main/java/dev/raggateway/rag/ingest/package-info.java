/**
 * Document ingestion (t09, t11): the write API saves a document and an outbox job in one transaction and
 * answers 202 (DECISIONS.md C4); a worker claims jobs, chunks, embeds in bounded batches outside any
 * transaction, and swaps the document's chunks in one transaction (D-47). Every row carries the tenant
 * (C17) and the ACL groups set by an admin key (C16).
 */
package dev.raggateway.rag.ingest;
