package dev.raggateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * Load shedding at the edge (D-46): at most {@code gateway.max-in-flight} {@code /v1} requests are served at once by
 * this instance; the next one gets 503 {@code overloaded} with {@code Retry-After: 1} straight away, before
 * authentication, body parsing or any Redis or provider call.
 *
 * <p>Why: with virtual threads nothing else bounds concurrency, and each open stream holds heap (the t08 run ran out
 * of heap at 4,000 concurrent streams on {@code -Xmx512m}). Shedding at a measured limit turns that into fast,
 * retryable errors for the excess while every admitted stream keeps working. The default (1,500) comes from that run:
 * 2,000 streams completed with the heap at its limit. Scale it with the heap and re-measure on other hardware.
 *
 * <p>{@link Semaphore#tryAcquire()} never blocks and never parks the caller, so it cannot pin a carrier. The permit
 * is released when the request's thread leaves the filter, which with the gateway's blocking model (D-03) is after
 * the response has been written and closed.
 */
public final class InFlightLimitFilter extends OncePerRequestFilter {

    private final int limit;
    private final Semaphore permits;
    private final Counter shed;
    private final ObjectMapper json;

    /** @param limit maximum concurrent {@code /v1} requests; 0 or less disables the filter */
    public InFlightLimitFilter(int limit, MeterRegistry registry, ObjectMapper json) {
        this.limit = limit;
        this.permits = new Semaphore(Math.max(limit, 0));
        this.json = json;
        this.shed = Counter.builder(MetricNames.REQUESTS_SHED)
                .description("Requests refused with 503 overloaded at the in-flight limit (D-46)").register(registry);
        Gauge.builder(MetricNames.REQUESTS_IN_FLIGHT, this, f -> f.inFlight())
                .description("/v1 requests being served by this instance").register(registry);
    }

    /** Requests currently holding a permit. */
    public int inFlight() {
        return limit <= 0 ? 0 : limit - permits.availablePermits();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limit <= 0 || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            shed.increment();
            reject(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var error = new GatewayException(ErrorCode.OVERLOADED,
                "this gateway instance is at its in-flight request limit; retry shortly");
        byte[] body = json.writeValueAsBytes(ErrorResponse.of(error, RequestIdFilter.requestId(request)));
        response.setStatus(ErrorCode.OVERLOADED.httpStatus());
        response.setHeader(GatewayHeaders.RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
