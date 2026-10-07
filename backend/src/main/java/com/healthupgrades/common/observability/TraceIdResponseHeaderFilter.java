package com.healthupgrades.common.observability;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Returns the current trace id to the caller as {@code X-Trace-Id}, so a reported failure carries the
 * key to its own log lines.
 *
 * <p>The header is written <b>before</b> the rest of the chain runs, which is what makes it survive the
 * responses that are not built on the request's own dispatch: {@code sendError} resets the response
 * buffer but not its headers, so a request the firewall refuses, rendered later by the container's error
 * page, still comes back with an id. The id is also left on the request for that page's body
 * ({@link TraceIdErrorAttributes}).
 *
 * <p>The header name is ours, not a standard: W3C defines {@code traceparent} for the request side, and
 * its response-side counterpart {@code traceresponse} is still a draft that nothing consumes.
 * {@code X-Trace-Id} carries the bare 32-character id, which is what someone can paste into a log
 * search. Registration and ordering live in {@link ObservabilityConfig}; this filter has to run after
 * the observation filter has opened a scope, or there would be no id to read.
 */
@RequiredArgsConstructor
public class TraceIdResponseHeaderFilter extends OncePerRequestFilter {

    /** Response header carrying the trace id. Exposed to cross-origin browsers by {@code SecurityConfig}. */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * Request attribute holding the id this filter sent, for {@link TraceIdErrorAttributes}. The
     * container's error page is rendered by a later dispatch that runs after the request's scope has
     * closed, so this is the only place it can still find the id the header carried (ADR-024).
     */
    static final String TRACE_ID_ATTRIBUTE = TraceIdResponseHeaderFilter.class.getName() + ".traceId";

    private final Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId.of(tracer).ifPresent(traceId -> {
            response.setHeader(TRACE_ID_HEADER, traceId);
            request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        });
        chain.doFilter(request, response);
    }
}
