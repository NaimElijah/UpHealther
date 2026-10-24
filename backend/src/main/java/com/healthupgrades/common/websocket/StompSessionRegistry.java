package com.healthupgrades.common.websocket;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.common.domain.port.out.LiveSocketPort;
import com.healthupgrades.common.domain.port.out.SessionStatusPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Keeps track of the open STOMP sockets and the session each was opened in, and closes them when that
 * session ends (ADR-054).
 *
 * <p>Follows Spring's own lifecycle events rather than the inbound frames: {@link SessionConnectedEvent}
 * carries the {@link StompPrincipal} {@code JwtChannelInterceptor} attached at CONNECT, and
 * {@link SessionDisconnectEvent} fires however a socket ends, a dropped connection included, which no
 * frame announces. Because the connect event comes after the CONNECT frame's own session check, a session
 * that ends between the two would slip past both; so a socket's session is checked once more as it is
 * recorded.
 *
 * <p>A socket is closed by sending it a STOMP ERROR frame through the client outbound channel. Spring
 * closes the WebSocket after sending any ERROR frame ({@code StompSubProtocolHandler.sendToClient}), so
 * the frame both ends the socket and tells the client why, through Spring's own serialised send path.
 * The entry itself goes when the disconnect event arrives, so the map mirrors the sockets actually open.
 *
 * <p>In memory, and so per instance, exactly as the broker is: a session ended on one instance could not
 * close a socket held by another. The application runs as one instance (requirements §5.3).
 */
@Component
@Slf4j
public class StompSessionRegistry implements LiveSocketPort {

    /**
     * The {@code message} header of the ERROR frame that closes a socket whose session ended. The browser
     * drops its access token on a frame with exactly these words and on no other ERROR, so they are a
     * contract with the frontend, which {@code SessionEndedFrameContractTest} checks.
     */
    public static final String SESSION_ENDED = "Session ended";

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final MessageChannel clientOutboundChannel; // the channel Spring writes client frames from
    private final SessionStatusPort sessionStatus; // inverted; the auth context answers it
    private final Map<String, StompPrincipal> openSockets = new ConcurrentHashMap<>(); // by socket id

    /**
     * @param clientOutboundChannel the broker configuration's channel to clients; named, because the
     *                              inbound and broker channels are message channels too
     * @param sessionStatus         asked once more as each socket is recorded, whether its session is
     *                              still live
     */
    public StompSessionRegistry(
            @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel, SessionStatusPort sessionStatus) {
        this.clientOutboundChannel = clientOutboundChannel;
        this.sessionStatus = sessionStatus;
    }

    /**
     * Starts tracking a socket once its CONNECT has been accepted, and closes it at once if its session
     * has ended in the meantime.
     *
     * @param event Spring's notice that a CONNECTED frame is about to go to the client
     */
    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        String socket =
                SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        // Every CONNECT that reaches CONNECTED was authenticated, so the principal is always one of ours;
        // anything else names no session and no account, and could never be matched to either.
        if (socket == null || !(event.getUser() instanceof StompPrincipal principal)) {
            return;
        }
        openSockets.put(socket, principal);
        // The session was checked at CONNECT, but it can end before this point, and a sweep that ran in
        // between could not see this socket. So it is asked again, after the put and never before it:
        // either the sweep runs after the put and finds the socket, or the end committed before this
        // check and the check sees it. Without this the socket would stay open until it reconnected.
        if (!stillLive(principal)) {
            close(socket);
        }
    }

    /**
     * Stops tracking a socket, however it ended.
     *
     * @param event Spring's notice that the socket has closed
     */
    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        openSockets.remove(event.getSessionId());
    }

    /** {@inheritDoc} */
    @Override
    public void closeForSession(UUID sessionId) {
        closeWhere(principal -> principal.sessionId().equals(sessionId));
    }

    /** {@inheritDoc} */
    @Override
    public void closeForUser(UUID userId) {
        String name = userId.toString();
        closeWhere(principal -> principal.name().equals(name));
    }

    /**
     * Whether the session that opened a socket is still live, failing closed: a socket whose session
     * cannot be confirmed is ended, and its client reconnects and is authenticated afresh. The fault is
     * handled here because Spring swallows a listener's exception, which would leave the socket open.
     */
    private boolean stillLive(StompPrincipal principal) {
        try {
            return sessionStatus.isActive(principal.sessionId(), UUID.fromString(principal.name()));
        } catch (RuntimeException unconfirmed) {
            log.warn(
                    "{} {}",
                    keyValue("event", "websocket.session-check-failed"),
                    keyValue("exception", unconfirmed.getClass().getName()));
            return false;
        }
    }

    private void closeWhere(Predicate<StompPrincipal> ended) {
        List<String> sockets = openSockets.entrySet().stream()
                .filter(entry -> ended.test(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        sockets.forEach(this::close);
        // DEBUG: the end of the session itself is already an audit entry, and this adds only how many
        // sockets went with it.
        log.debug("Closed the sockets opened in ended sessions {}", keyValue("sockets", sockets.size()));
    }

    private void close(String socket) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(SESSION_ENDED);
        error.setSessionId(socket);
        try {
            clientOutboundChannel.send(MessageBuilder.createMessage(EMPTY_PAYLOAD, error.getMessageHeaders()));
        } catch (MessagingException unreachable) {
            // Handled rather than thrown: the session's end has already committed, and one socket that
            // cannot be reached must not keep the others open or fail the request that ended it. The
            // type and not the message, since this repeats per socket (ADR-010).
            log.warn(
                    "{} {} {}",
                    keyValue("event", "websocket.close-failed"),
                    keyValue("socket", socket),
                    keyValue("exception", unreachable.getClass().getName()));
        }
    }
}
