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
 * The chain unwinds {@code afterSendCompletion} in reverse, so a rejected connection is still inside
 * this span and is recorded on it. See
 * {@code docs/ADRs/ADR-007-request-correlation-through-micrometer-tracing.md}.
 */
@Component
@RequiredArgsConstructor
public class StompTracingChannelInterceptor implements ExecutorChannelInterceptor {

    /** Internal header carrying the sending span's context to the handling thread. Never sent to a client. */
    private static final String TRACE_CONTEXT_HEADER = "healthupgrades-trace-context";

    /**
     * Spans opened on this thread and not yet closed. A deque rather than a single slot because a
     * handler may itself send on another channel, nesting a second span inside the first.
     */
    private static final ThreadLocal<Deque<Scope>> OPEN_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        Span span = propagator
                .extract(accessor, (carrier, key) -> carrier.getFirstNativeHeader(key))
                .name(spanName(accessor))
                .start();
        open(span);

        MessageHeaderAccessor mutable = MessageHeaderAccessor.getMutableAccessor(message);
        mutable.setHeader(TRACE_CONTEXT_HEADER, span.context());
        return MessageBuilder.createMessage(message.getPayload(), mutable.getMessageHeaders());
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        close(ex);
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        Span.Builder builder = tracer.spanBuilder().name(spanName(StompHeaderAccessor.wrap(message)) + " handle");
        if (message.getHeaders().get(TRACE_CONTEXT_HEADER) instanceof TraceContext parent) {
            builder.setParent(parent);
        }
        open(builder.start());
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        close(ex);
    }

    private void open(Span span) {
        OPEN_SCOPES.get().push(new Scope(span, tracer.withSpan(span)));
    }

    /**
     * Ends the innermost span open on this thread, recording {@code ex} on it when the work failed.
     *
     * <p>Tolerates an empty deque: an interceptor earlier in the chain can reject a message before this
     * one's {@code preSend} ran, and Spring still calls the completion hook.
     */
    private void close(Exception ex) {
        Deque<Scope> scopes = OPEN_SCOPES.get();
        Scope scope = scopes.poll();
        if (scope == null) {
            return;
        }
        if (scopes.isEmpty()) {
            OPEN_SCOPES.remove();
        }
        if (ex != null) {
            scope.span.error(ex);
        }
        scope.inScope.close();
        scope.span.end();
    }

    /** {@code SEND /app/x}, or just the command when the frame has no destination. */
    private static String spanName(StompHeaderAccessor accessor) {
        String command = accessor.getCommand() == null ? "MESSAGE" : accessor.getCommand().name();
        return accessor.getDestination() == null ? command : command + " " + accessor.getDestination();
    }

    private record Scope(Span span, Tracer.SpanInScope inScope) {
    }
}
