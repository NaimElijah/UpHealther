package com.healthupgrades.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the three shapes "there is no trace id" arrives in.
 *
 * <p>Each is reachable in production — an uninstrumented thread, tracing switched off, an invalid
 * context — and each would otherwise surface as a different bug: a {@code NullPointerException}, an
 * {@code X-Trace-Id:} header with nothing after it, or a body advertising an id of 32 zeros that
 * matches no log line.
 */
class CorrelationIdTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void GivenASpanIsInScope_WhenTheIdIsRead_ThenItIsTheSpansTraceId() {
        SimpleTracer tracer = new SimpleTracer();
        Span span = tracer.nextSpan().start();
        // Set explicitly: SimpleTracer does not generate trace ids, so asserting against whatever it
        // produced would compare a blank id with a blank id and hold whatever this helper did.
        ((SimpleTraceContext) span.context()).setTraceId(TRACE_ID);

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(CorrelationId.of(tracer)).contains(TRACE_ID);
        }
    }

    @Test
    void GivenNoSpanIsInScope_WhenTheIdIsRead_ThenNothingIsReturned() {
        // A real tracer returns null rather than a blank span when nothing is being traced.
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        assertThat(CorrelationId.of(tracer)).isEmpty();
    }

    @Test
    void GivenTracingIsDisabled_WhenTheIdIsRead_ThenNothingIsReturned() {
        assertThat(CorrelationId.of(Tracer.NOOP)).isEmpty();
    }

    @Test
    void GivenAnInvalidTraceContext_WhenTheIdIsRead_ThenTheAllZeroIdIsRejected() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("0".repeat(32));

        assertThat(CorrelationId.of(tracer)).isEmpty();
    }
}
