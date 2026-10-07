package com.healthupgrades.common.observability;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * Boot's error body, plus the trace id the request was answered under (NFR-21, ADR-024).
 *
 * <p>This is the body of the container's error page: what a request the firewall refuses, or an
 * exception that escapes a filter, comes back with. Neither reaches {@code GlobalExceptionHandler}.
 * Tomcat renders both through an ERROR dispatch to {@code /error}, which runs after the request's
 * observation has closed. So the id cannot be read from the tracer here, the way {@link CorrelationId}
 * reads it everywhere else. It is taken instead from the request attribute
 * {@link TraceIdResponseHeaderFilter} recorded when it set {@code X-Trace-Id}, which makes the header
 * and the body the same value by construction.
 *
 * <p>The key is {@code traceId}, as on every body {@code GlobalExceptionHandler} builds, so a client
 * reads one field whichever path produced the error. It is left out when the request was not traced,
 * rather than sent empty. Everything else is Boot's, bounded by the {@code server.error.include-*}
 * defaults: since the page is reachable without a token, changing one of those is a security decision.
 */
public class TraceIdErrorAttributes extends DefaultErrorAttributes {

    private static final String TRACE_ID = "traceId";

    /**
     * Builds Boot's error attributes and adds the request's trace id when it has one.
     *
     * @param webRequest the request being rendered by the error page
     * @param options    which of Boot's optional attributes to include
     * @return Boot's attributes, with {@code traceId} added if {@code X-Trace-Id} was sent
     */
    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        Object traceId = webRequest.getAttribute(TraceIdResponseHeaderFilter.TRACE_ID_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (traceId != null) {
            attributes.put(TRACE_ID, traceId);
        }
        return attributes;
    }
}
