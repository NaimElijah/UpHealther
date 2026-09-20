package com.healthupgrades.common.websocket;

import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP connections and authorises the frames that follow.
 *
 * <p>On the CONNECT frame it checks the bearer token supplied in the {@code Authorization} native header,
 * through the same {@link BearerTokenAuthenticator} the HTTP filter uses, and attaches a
 * {@link StompPrincipal} named by user id to the session, so per-user messaging is routed to the right
 * account. A missing or unusable token refuses the connection.
 *
 * <p>Authenticating the connection is not enough on its own. Once a session exists it may name any
 * destination it likes, and the broker destination a user queue resolves to is guessable, so the later
 * frames are authorised too:
 * <ul>
 *   <li>a SUBSCRIBE must come from an authenticated session and name {@link #ALLOWED_SUBSCRIPTION}
 *       exactly — naming the resolved {@code /queue/notifications-user…} of another session, or any
 *       {@code /topic}, is refused;</li>
 *   <li>a SEND is refused outright: the application declares no {@code @MessageMapping}, so there is
 *       nothing server-side for one to reach and anything sending one is probing;</li>
 *   <li>heartbeats, UNSUBSCRIBE and DISCONNECT pass — they carry no destination to abuse, and refusing
 *       a DISCONNECT would leave sessions to be torn down by timeout instead of closed cleanly.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * The one destination a client may subscribe to.
     *
     * <p>It is the user-prefixed form of the queue the notification push adapter writes to, which the
     * broker resolves per session into {@code /queue/notifications-user<sessionId>}. The literal is
     * repeated here rather than imported from that adapter because {@code common} must not depend on a
     * bounded context; {@code StompNotificationPushAdapterTest} asserts the two have not drifted apart.
     */
    public static final String ALLOWED_SUBSCRIPTION = "/user/queue/notifications";

    private final BearerTokenAuthenticator authenticator;

    /**
     * Authenticates a connecting frame and authorises every other frame that can name a destination.
     *
     * @param message the inbound STOMP message
     * @param channel the client inbound channel
     * @return the same message, with the session principal attached on CONNECT
     * @throws IllegalArgumentException if a connecting frame carries no bearer token or an unusable one,
     *                                  or if a later frame is not one this application accepts. Spring
     *                                  turns this into a refused connection, which is the only way to
     *                                  reject a STOMP frame from an interceptor
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor == null ? null : accessor.getCommand();
        if (command == null) {
            // A heartbeat carries no command: nothing to authenticate, nothing to authorise. Reading one
            // as an unauthorised frame would close every idle socket on its first keepalive.
            return message;
        }
        switch (command) {
            // STOMP is the 1.2 spec's alias for CONNECT. Without it here a client could open a session
            // with the alias and skip authentication; it would still fail at its first SUBSCRIBE, for
            // want of a principal, but failing at the door is the honest place to fail.
            case CONNECT, STOMP -> accessor.setUser(authenticate(accessor));
            case SUBSCRIBE -> authoriseSubscription(accessor);
            case SEND -> refuse("a SEND, which no server-side mapping receives");
            default -> { /* UNSUBSCRIBE, DISCONNECT, ACK, NACK and the transaction frames name nothing */ }
        }
        return message;
    }

    private StompPrincipal authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            // DEBUG for both refusals: /ws is a public endpoint and a reconnecting client with a stale
            // token is routine. The reason is recorded because the two are diagnosed differently: a
            // missing header is a client bug, an unusable token is a session that ended. Spring turns the
            // exception into a refused connection and does not log one itself.
            log.debug("Refused a STOMP CONNECT: no bearer token");
            throw new IllegalArgumentException("Missing or malformed Authorization header on STOMP CONNECT");
        }
        SecurityUser principal = authenticator.authenticate(authorization.substring(BEARER_PREFIX.length()))
                .orElseThrow(() -> {
                    log.debug("Refused a STOMP CONNECT: the token is not usable");
                    return new IllegalArgumentException("Unusable token on STOMP CONNECT");
                });
        return new StompPrincipal(principal.getId().toString());
    }

    private void authoriseSubscription(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            refuse("a SUBSCRIBE from a session that never connected");
        }
        if (!ALLOWED_SUBSCRIPTION.equals(accessor.getDestination())) {
            refuse("a SUBSCRIBE to a destination this application does not publish to");
        }
    }

    private void refuse(String what) {
        // WARN rather than DEBUG, and the opposite call from a rejected CONNECT: a stale token is
        // ordinary, whereas a correct client never sends either of these. The destination is deliberately
        // left out of the line - it is text the client chose, and writing it down would copy whatever it
        // contains, newlines included, into the log.
        log.warn("Refused {}", what);
        throw new IllegalArgumentException("Refused " + what);
    }
}
