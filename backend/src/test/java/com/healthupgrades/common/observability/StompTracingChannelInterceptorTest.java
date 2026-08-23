package com.healthupgrades.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the span lifecycle across the two threads a STOMP frame touches.
 *
 * <p>The hazard being guarded is structural: {@code preSend}/{@code afterSendCompletion} run on the
 * sending thread and only bracket the dispatch, while the handler runs later on a channel-executor
 * thread. Covering only the first pair would leave every handler log line uncorrelated, and the gap
 * would be invisible until someone went looking for a line that was never tagged.
 */
class StompTracingChannelInterceptorTest {

    private final SimpleTracer tracer = new SimpleTracer();
    private final MessageChannel channel = mock(MessageChannel.class);
    private final MessageHandler handler = mock(MessageHandler.class);
    private final StompTracingChannelInterceptor interceptor =
            new StompTracingChannelInterceptor(tracer, propagatorStartingAFreshSpan());

    /** Boot's real tracing stack, with no web server or datasource, so {@code mvn test} needs no database. */
    private final ApplicationContextRunner realTracing = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                    org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class))
            .withPropertyValues("management.tracing.sampling.probability=1.0");

    @Test
    void GivenAFrameIsSent_WhenTheSendCompletes_ThenItsSpanIsStartedAndEnded() {
        Message<?> message = frame(StompCommand.SEND, "/app/ping");

        Message<?> forwarded = interceptor.preSend(message, channel);
        assertThat(tracer.currentSpan()).isNotNull();
        interceptor.afterSendCompletion(forwarded, channel, true, null);

        // Asserting the span was ended, not that the test double forgot it: SimpleTracer keeps every
        // span it made, and an unended span is the leak this interceptor exists to avoid.
        assertThat(tracer.getSpans()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("SEND");
            assertThat(span.getEndTimestamp()).isNotNull();
        });
    }

    @Test
    void GivenTheHandlerRunsOnAnotherThread_WhenItIsInvoked_ThenItsSpanContinuesTheSendingTrace() {
        // The crux of the two-thread design, and the one assertion SimpleTracer cannot make honestly:
        // it does not generate trace ids, so a comparison between two of its spans would compare "" with
        // "" and hold however the parent was wired. This runs against the real tracer instead.
        realTracing.run(context -> {
            Tracer realTracer = context.getBean(Tracer.class);
            StompTracingChannelInterceptor traced =
                    new StompTracingChannelInterceptor(realTracer, context.getBean(Propagator.class));

            Message<?> forwarded = traced.preSend(frame(StompCommand.SEND, "/app/ping"), channel);
            String sendingTraceId = realTracer.currentSpan().context().traceId();

            // On another thread deliberately. Handling on the sending thread would inherit the ambient
            // span and the assertion would hold even if the context were never carried on the message -
            // which is exactly the bug this guards.
            String handlingTraceId = onAnotherThread(() -> {
                traced.beforeHandle(forwarded, channel, handler);
                String id = realTracer.currentSpan().context().traceId();
                traced.afterMessageHandled(forwarded, channel, handler, null);
                return id;
            });
            traced.afterSendCompletion(forwarded, channel, true, null);

            assertThat(sendingTraceId).isNotBlank();
            assertThat(handlingTraceId).isEqualTo(sendingTraceId);
        });
    }

    @Test
    void GivenTheSpanContextTravels_WhenTheFrameIsEncoded_ThenItIsNotANativeHeader() {
        // A native header would be written to the wire by StompEncoder and shipped to the browser.
        Message<?> forwarded = interceptor.preSend(frame(StompCommand.SEND, "/app/ping"), channel);

        assertThat(StompHeaderAccessor.wrap(forwarded).toNativeHeaderMap())
                .doesNotContainKey("healthupgrades-trace-context");

        // Completed rather than abandoned: a preSend left open would leave a scope on this thread and
        // the next test would pop it instead of exercising its own empty-deque path.
        interceptor.afterSendCompletion(forwarded, channel, true, null);
    }

    @Test
    void GivenAConnectIsRejected_WhenTheChainUnwinds_ThenTheFailureIsRecordedOnTheSpan() {
        // JwtChannelInterceptor throws from preSend on a bad token. That is the event most worth
        // correlating, and it is only visible to an interceptor registered ahead of it.
        Message<?> forwarded = interceptor.preSend(frame(StompCommand.CONNECT, null), channel);

        interceptor.afterSendCompletion(forwarded, channel, false, new IllegalArgumentException("bad token"));

        assertThat(tracer.getSpans()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("CONNECT");
            assertThat(span.getError()).hasMessage("bad token");
        });
    }

    @Test
    void GivenTheChannelRefusesTheMessage_WhenTheSendCompletes_ThenTheSpanSaysSo() {
        // A refused send carries no exception, so without the tag it ends looking exactly like a
        // delivered one - and a push that never arrived is the case worth finding in a trace.
        Message<?> forwarded = interceptor.preSend(frame(StompCommand.SEND, "/app/ping"), channel);

        interceptor.afterSendCompletion(forwarded, channel, false, null);

        assertThat(tracer.getSpans()).singleElement().satisfies(span ->
                assertThat(span.getTags()).containsEntry("stomp.send.refused", "true"));
    }

    @Test
    void GivenAnInterceptorAheadOfThisOneRejectedTheFrame_WhenCompletionRuns_ThenNothingBlowsUp() {
        // Spring calls the completion hook even when this interceptor's preSend never ran.
        interceptor.afterSendCompletion(frame(StompCommand.SEND, "/app/ping"), channel, false, null);

        assertThat(tracer.getSpans()).isEmpty();
    }

    /** Runs {@code work} on a thread of its own, the way the channel executor invokes a handler. */
    private static String onAnotherThread(Supplier<String> work) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(work::get).get();
        } finally {
            executor.shutdownNow();
        }
    }

    private static Message<?> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * A propagator that finds nothing on the carrier and hands back a fresh root span builder — the
     * normal case for a browser client, which sends no {@code traceparent}.
     */
    private Propagator propagatorStartingAFreshSpan() {
        return new Propagator() {
            @Override
            public java.util.List<String> fields() {
                return java.util.List.of();
            }

            @Override
            public <C> void inject(io.micrometer.tracing.TraceContext context, C carrier, Setter<C> setter) {
                // Nothing to inject: this test never crosses a process boundary.
            }

            @Override
            public <C> Span.Builder extract(C carrier, Getter<C> getter) {
                return tracer.spanBuilder();
            }
        };
    }
}
