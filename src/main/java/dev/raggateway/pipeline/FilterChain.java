package dev.raggateway.pipeline;

import dev.raggateway.core.RequestContext;
import dev.raggateway.error.GatewayException;

import java.util.List;

/**
 * Runs filters in order, then the terminal (LLD, unchanged). Created fresh for each request, so the
 * index is never shared between threads.
 */
public final class FilterChain {

    private final List<GatewayFilter> filters;
    private final Terminal terminal;
    private int index = 0;

    public FilterChain(List<GatewayFilter> filters, Terminal terminal) {
        this.filters = List.copyOf(filters);
        this.terminal = terminal;
    }

    /** Hand the request to the next filter, or to the terminal after the last filter. */
    public void next(RequestContext ctx, StreamSink sink) throws GatewayException {
        if (index < filters.size()) filters.get(index++).handle(ctx, sink, this);
        else terminal.call(ctx, sink);
    }
}
