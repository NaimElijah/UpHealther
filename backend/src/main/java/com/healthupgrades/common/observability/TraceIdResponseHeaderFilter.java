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
 * responses that are not built by {@code GlobalExceptionHandler}: {@code sendError} resets the response
 * buffer but not its headers, so a 401 produced inside the Spring Security chain — which never reaches
 * the advice, because no {@code AuthenticationEntryPoint} is configured — still comes back with an id.
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

    private final Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId.of(tracer).ifPresent(traceId -> response.setHeader(TRACE_ID_HEADER, traceId));
        chain.doFilter(request, response);
    }
}
