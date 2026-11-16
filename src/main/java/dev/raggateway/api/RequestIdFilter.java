package dev.raggateway.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a fresh random UUID (DECISIONS.md D-15): returned in {@code X-Request-Id}, put in
 * the logging MDC as {@code requestId} for every log line of the request, and later written to the
 * request-log row. An incoming {@code X-Request-Id} is ignored: a client-chosen id could collide or be
 * spoofed. Runs first, so 401 and 400 responses carry the id too. Also records the receive time used as
 * the request's start.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    /** MDC key; the log pattern in application.yml prints it. */
    public static final String MDC_KEY = "requestId";
    static final String ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    static final String START_ATTRIBUTE = RequestIdFilter.class.getName() + ".startNanos";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String id = UUID.randomUUID().toString();
        request.setAttribute(ID_ATTRIBUTE, id);
        request.setAttribute(START_ATTRIBUTE, start);
        response.setHeader(GatewayHeaders.REQUEST_ID, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The id given to this request, or null if the filter did not run (only outside the servlet chain). */
    public static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(ID_ATTRIBUTE);
    }

    /** {@link System#nanoTime()} when the request was received. */
    public static long startNanos(HttpServletRequest request) {
        Object start = request.getAttribute(START_ATTRIBUTE);
        return start instanceof Long l ? l : System.nanoTime();
    }
}
