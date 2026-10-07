package com.healthupgrades.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-21 on the one path {@code GlobalExceptionHandler} never sees: the body Boot's error controller
 * renders during a container ERROR dispatch (#105, ADR-024).
 *
 * <p>The body is built here only after the span's scope has closed, which is the state the ERROR dispatch
 * runs in. A test that built it inside the scope would pass even if the id were read from the tracer at
 * that point, which is exactly what cannot work in production.
 */
class TraceIdErrorAttributesTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/upgrades;x=y");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final TraceIdErrorAttributes errorAttributes = new TraceIdErrorAttributes();

    @Test
    void GivenTheRequestWasTraced_WhenItsErrorBodyIsBuiltAfterTheScopeClosed_ThenItCarriesTheHeadersTraceId()
            throws Exception {
        SimpleTracer tracer = new SimpleTracer();
        Span span = tracer.nextSpan().start();
        ((SimpleTraceContext) span.context()).setTraceId(TRACE_ID);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            new TraceIdResponseHeaderFilter(tracer).doFilter(request, response, new MockFilterChain());
        }

        Map<String, Object> body = errorBodyFor(400);

        assertThat(body).containsEntry("traceId", TRACE_ID);
        assertThat(body.get("traceId")).isEqualTo(response.getHeader(TraceIdResponseHeaderFilter.TRACE_ID_HEADER));
        assertThat(body).containsEntry("status", 400);
    }

    @Test
    void GivenTheRequestWasNotTraced_WhenTheErrorBodyIsBuilt_ThenItHasNoTraceIdKey() throws Exception {
        // As on every other error body: an absent id is left out, never sent as an empty string that
        // looks like one that was lost.
        new TraceIdResponseHeaderFilter(Tracer.NOOP).doFilter(request, response, new MockFilterChain());

        Map<String, Object> body = errorBodyFor(400);

        assertThat(body).doesNotContainKey("traceId");
    }

    /** What Boot's error controller would render for this request, given the status the container chose. */
    private Map<String, Object> errorBodyFor(int status) {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        return errorAttributes.getErrorAttributes(new ServletWebRequest(request), ErrorAttributeOptions.defaults());
    }
}
