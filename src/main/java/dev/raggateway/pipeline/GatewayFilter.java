package dev.raggateway.pipeline;

import dev.raggateway.core.RequestContext;
import dev.raggateway.error.GatewayException;

/**
 * One step of the pipeline (Chain of Responsibility, LLD). A filter does exactly one of:
 * <ol>
 *   <li>reject: throw {@link GatewayException} <em>before</em> calling {@code chain.next} (400, 403, 429);</li>
 *   <li>answer itself: emit tokens and one terminal event on {@code sink} and return (cache hit);</li>
 *   <li>continue: call {@code chain.next(ctx, sinkOrWrapper)} exactly once.</li>
 * </ol>
 * {@code chain.next} returns when the request is finished, so end-of-request work belongs in a
 * {@code finally} block (see package doc). Spring beans implementing this interface are picked up by the
 * pipeline and sorted by {@link org.springframework.core.annotation.Order} using {@link FilterOrder}.
 */
public interface GatewayFilter {

    void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException;
}
