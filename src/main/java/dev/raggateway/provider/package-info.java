/**
 * Provider adapters (LLD Adapter pattern). One {@link dev.raggateway.provider.LlmProvider} per vendor:
 * Claude through the official Anthropic Java SDK (t01, DECISIONS.md D-04/D-05/D-06) and Ollama through the
 * JDK HttpClient (t03). Callers never see vendor types.
 */
package dev.raggateway.provider;
