package com.healthupgrades.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the half of issue #43's second criterion that the error body cannot cover: the id has to come
 * back on responses this application never builds, above all the 401 that Spring Security writes
 * itself.
 */
class TraceIdResponseHeaderFilterTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/upgrades");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void GivenARequestIsBeingTraced_WhenItIsServed_ThenTheResponseCarriesTheTraceId() throws Exception {
        SimpleTracer tracer = new SimpleTracer();
        Span span = tracer.nextSpan().start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            new TraceIdResponseHeaderFilter(tracer).doFilter(request, response, new MockFilterChain());
        }

        assertThat(response.getHeader(TraceIdResponseHeaderFilter.TRACE_ID_HEADER))
                .isEqualTo(span.context().traceId());
    }

    @Test
    void GivenNothingIsBeingTraced_WhenARequestIsServed_ThenNoEmptyHeaderIsSent() throws Exception {
        // Tracer.NOOP reports a span whose trace id is the empty string; an "X-Trace-Id:" header with
        // nothing after it is worse than none, because it looks like an id that was lost.
        new TraceIdResponseHeaderFilter(Tracer.NOOP).doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdResponseHeaderFilter.TRACE_ID_HEADER)).isNull();
    }

    @Test
    void GivenTheChainSendsAnError_WhenTheResponseIsWritten_ThenTheTraceIdSurvivesTheBufferReset() throws Exception {
        // The 401 case: sendError resets the buffer, not the headers, and only a header written before
        // the chain runs is still there afterwards.
        SimpleTracer tracer = new SimpleTracer();
        Span span = tracer.nextSpan().start();
        MockFilterChain sendsError = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) throws IOException {
                ((MockHttpServletResponse) res).sendError(401);
            }
        };

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            new TraceIdResponseHeaderFilter(tracer).doFilter(request, response, sendsError);
        }

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(TraceIdResponseHeaderFilter.TRACE_ID_HEADER)).isNotBlank();
    }
}
