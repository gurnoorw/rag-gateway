/**
 * RAG query side (t10, t12): the keyword and vector legs, reciprocal rank fusion, and the answer endpoint that
 * sends the built prompt through the same {@link dev.raggateway.pipeline.GatewayPipeline} as chat (DECISIONS.md
 * C17, C18, C19, D-49). Every SQL statement here filters by tenant, collection and the caller key's ACL groups
 * inside the query; nothing is filtered after the rows leave the database.
 */
package dev.raggateway.rag.query;
