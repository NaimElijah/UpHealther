package com.healthupgrades.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Gives every STOMP frame a trace id, on both threads that touch it.
 *
 * <p>A plain {@code ChannelInterceptor} is not enough here, and the reason is easy to miss:
 * {@code ExecutorSubscribableChannel.sendInternal} only <em>dispatches</em> the message, so
 * {@code preSend} and {@code afterSendCompletion} bracket the dispatch on the sending thread, not the
 * handling. The handler runs later, on a channel-executor thread, bracketed by
 * {@link #beforeHandle} and {@link #afterMessageHandled}. Both halves need a scope, which is why this
 * implements {@link ExecutorChannelInterceptor} — the same reason Spring Security's
 * {@code SecurityContextChannelInterceptor} does.
 *
 * <p>The two halves are joined by stashing the sending span's {@link TraceContext} in a
 * <b>non-native</b> message header. Non-native headers are never written to the wire by
 * {@code StompEncoder}, so nothing leaks to the client; the handler side reads it back and starts a
 * child span, rather than re-entering the same span, so a message with several subscribers cannot end
 * one span twice.
 *
 * <p>Registration order matters and is asserted: this interceptor is registered <em>before</em>
 * {@code JwtChannelInterceptor}, which rejects a bad CONNECT by throwing from its own {@code preSend}.
 * The chain unwinds completion callbacks in reverse, so a rejected connection is still inside this span
 * and is recorded on it. See
 * {@code docs/ADRs/ADR-007-request-correlation-through-micrometer-tracing.md}.
 */
@Component
@RequiredArgsConstructor
public class StompTracingChannelInterceptor implements ExecutorChannelInterceptor {

    /** Internal header carrying the sending span's context to the handling thread. Never sent to a client. */
    private static final String TRACE_CONTEXT_HEADER = "healthupgrades-trace-context";

    /** Marks a span whose send was refused, which otherwise ends looking exactly like a delivered one. */
    private static final String SEND_REFUSED_TAG = "stomp.send.refused";

    /**
     * Spans opened on the current thread and not yet closed. A deque rather than a single slot because a
     * handler may itself send on another channel, nesting a second span inside the first.
     *
     * <p>Deliberately an instance field: this is a singleton registered on three channels, and a static
     * would additionally braid together every other instance — including the ones tests construct.
     */
    private final ThreadLocal<Deque<Scope>> openScopes = ThreadLocal.withInitial(ArrayDeque::new);

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        Span span = propagator
                .extract(accessor, (carrier, key) -> carrier.getFirstNativeHeader(key))
                .name(spanName(accessor))
                .start();
        try {
            MessageHeaderAccessor mutable = MessageHeaderAccessor.getMutableAccessor(message);
            mutable.setHeader(TRACE_CONTEXT_HEADER, span.context());
            Message<?> forwarded = MessageBuilder.createMessage(message.getPayload(), mutable.getMessageHeaders());
            // Opened only once nothing above can still throw. Spring advances its interceptor index after
            // preSend returns, so an interceptor that throws is skipped by triggerAfterSendCompletion -
            // a scope opened before the throw would never be closed and would attach this frame's trace
            // id to every later frame on the same pooled thread.
            open(span);
            return forwarded;
        } catch (RuntimeException e) {
            span.error(e);
            span.end();
            throw e;
        }
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        close(ex, sent);
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        Span.Builder builder = tracer.spanBuilder().name(spanName(StompHeaderAccessor.wrap(message)) + " handle");
        if (message.getHeaders().get(TRACE_CONTEXT_HEADER) instanceof TraceContext parent) {
            builder = builder.setParent(parent);
        }
        open(builder.start());
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        close(ex, true);
    }

    private void open(Span span) {
        openScopes.get().push(new Scope(span, tracer.withSpan(span)));
    }

    /**
     * Ends the innermost span open on this thread, recording why the work stopped.
     *
     * <p>Tolerates an empty deque: an interceptor earlier in the chain can reject a message before this
     * one's {@code preSend} ran, and Spring still calls the completion hook.
     *
     * @param ex   the failure, or null when the work completed
     * @param sent whether the channel accepted the message; a refused send carries no exception, so
     *             without the tag it would end indistinguishable from a delivered one
     */
    private void close(Exception ex, boolean sent) {
        Deque<Scope> scopes = openScopes.get();
        Scope scope = scopes.poll();
        if (scopes.isEmpty()) {
            openScopes.remove();
        }
        if (scope == null) {
            return;
        }
        if (ex != null) {
            scope.span.error(ex);
        }
        if (!sent) {
            scope.span.tag(SEND_REFUSED_TAG, "true");
        }
        scope.inScope.close();
        scope.span.end();
    }

    /**
     * The STOMP command, and deliberately nothing else.
     *
     * <p>The destination is not in the name on purpose: {@code convertAndSendToUser} produces
     * {@code /user/{userId}/queue/notifications} and a session-suffixed queue after that, which would put
     * a user id into observability data — contradicting NFR-6 — and give the name unbounded cardinality.
     */
    private static String spanName(StompHeaderAccessor accessor) {
        return accessor.getCommand() == null ? "MESSAGE" : accessor.getCommand().name();
    }

    private record Scope(Span span, Tracer.SpanInScope inScope) {
    }
}
