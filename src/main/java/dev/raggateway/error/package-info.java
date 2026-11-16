/**
 * Error model (DECISIONS.md C15). Every client-visible failure is a {@link dev.raggateway.error.GatewayException}
 * with an {@link dev.raggateway.error.ErrorCode}; the code fixes the HTTP status and the OpenAI-style
 * error {@code type}. The HTTP edge renders it as JSON before the first token and as an SSE error event after.
 */
package dev.raggateway.error;
