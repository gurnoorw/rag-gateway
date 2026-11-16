/**
 * The request pipeline (LLD "The request pipeline"): ordered {@link dev.raggateway.pipeline.GatewayFilter}s,
 * a {@link dev.raggateway.pipeline.Terminal} that calls providers, and {@link dev.raggateway.pipeline.StreamSink}
 * decorators. Order and the reasons for it: DECISIONS.md C3 and {@link dev.raggateway.pipeline.FilterOrder}.
 *
 * <p>Key rule (DECISIONS.md C23 / D-14): {@code chain.next(...)} is a blocking call that returns only when
 * the request has finished (completed, failed or cancelled). End-of-request work (refund, budget, cache
 * write) therefore goes in a {@code finally} block after {@code chain.next}, which runs exactly once on
 * every exit path. Sink decorators only observe; they never settle.
 */
package dev.raggateway.pipeline;
