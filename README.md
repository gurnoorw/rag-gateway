# rag-gateway

An LLM gateway with an OpenAI-compatible `POST /v1/chat/completions` endpoint and a RAG endpoint on top, built in Java 21 and Spring Boot 3.

The gateway sits between applications and LLM providers. It handles per-tenant token rate limits and budgets, provider fallback with retries and circuit breakers, exact-match response caching, model routing, JSON-schema output validation, SSE streaming, cost attribution and Prometheus metrics. The RAG path adds document ingestion, hybrid retrieval (Postgres full-text + pgvector, merged with reciprocal rank fusion) and chunk-level access control.

**Status:** under construction. Benchmark numbers will be added once they are measured; every number will be labelled with the hardware it ran on.

## Docs

- [Roadmap](docs/ROADMAP.md): scope, task plan, stack
- [High-level design](docs/HLD.md)
- [Low-level design](docs/LLD.md)
- [Concepts and trade-offs](docs/CONCEPTS.md)
