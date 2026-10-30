package com.healthupgrades.common.websocket;

import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import com.healthupgrades.common.security.BearerAuthentication;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * account. The principal also keeps the server-side session the token was issued within. A missing or
 * unusable token refuses the connection.
 *
 * <p>Authenticating the connection is not enough on its own. Once a session exists it may name any
 * destination it likes, and the broker destination a user queue resolves to is guessable, so the later
 * frames are authorised too:
 * <ul>
 *   <li>a SUBSCRIBE must come from an authenticated session and name {@link #ALLOWED_SUBSCRIPTION}
 *       exactly — naming the resolved {@code /queue/notifications-user…} of another session, or any
 *       {@code /topic}, is refused;</li>
 *   <li>a socket holds one subscription for its life: a second SUBSCRIBE is refused, whatever came
 *       between. Each subscription is registered with the broker, so a socket streaming SUBSCRIBE frames
 *       with fresh ids would otherwise grow the heap without limit (S5.A5). An UNSUBSCRIBE frees
 *       nothing, because it could be raced: Spring hands each inbound frame to a pool, and an UNSUBSCRIBE
 *       that reached the broker before its SUBSCRIBE would remove nothing and leave that subscription
 *       held. The SPA subscribes once per socket and never unsubscribes;</li>
 *   <li>a SEND is refused outright: the application declares no {@code @MessageMapping}, so there is
 *       nothing server-side for one to reach and anything sending one is probing;</li>
 *   <li>heartbeats, UNSUBSCRIBE and DISCONNECT pass — they carry no destination to abuse, and refusing
 *       a DISCONNECT would leave sessions to be torn down by timeout instead of closed cleanly. An
 *       UNSUBSCRIBE still reaches the broker, which drops the subscription it names; it only does not
 *       let the socket take another.</li>
 * </ul>
 *
 * <p>Every CONNECT that presents a credential is audited as {@code auth.connect}, allowed or refused,
 * because opening a socket is an authentication outcome (NFR-23): the socket stays signed in as that user
 * for as long as it is open. One that presents none is not an attempt, as a refresh with no cookie is not.
 * The frames after it are not: refusing one is an authorisation decision on a connection already
 * authenticated, so it stays a WARN line rather than an audit entry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    /** A refused CONNECT names nobody: a token that does not authenticate proves nothing about whose it was. */
    private static final AuditEvent REFUSED_CONNECT =
            new AuditEvent(AuditAction.AUTH_CONNECT, null, null, AuditOutcome.REFUSED);

    /**
     * The one destination a client may subscribe to.
     *
     * <p>It is the user-prefixed form of the queue the notification push adapter writes to, which the
     * broker resolves per session into {@code /queue/notifications-user<sessionId>}. The literal is
     * repeated here rather than imported from that adapter because {@code common} must not depend on a
     * bounded context; {@code StompNotificationPushAdapterTest} asserts the two have not drifted apart.
     */
    public static final String ALLOWED_SUBSCRIPTION = "/user/queue/notifications";

    /**
     * The session attribute that holds the id of the subscription a socket has taken, for the rest of its
     * life. Spring hands every frame of one WebSocket session the same attributes map, so it is per
     * socket, and it is discarded with the socket.
     */
    private static final String HELD_SUBSCRIPTION = JwtChannelInterceptor.class.getName() + ".heldSubscription";

    private final BearerTokenAuthenticator authenticator;
    private final AuditTrail auditTrail; // records every CONNECT, allowed or refused

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
            default -> {
                /* UNSUBSCRIBE, DISCONNECT, ACK, NACK and the transaction frames name nothing */
            }
        }
        return message;
    }

    private StompPrincipal authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            // DEBUG for both refusals: /ws is a public endpoint and a reconnecting client with a stale
            // token is routine. The reason is recorded because the two are diagnosed differently: a
            // missing header is a client with no session left to offer, an unusable token is a session
            // that ended. Spring turns the exception into a refused connection, and for a CONNECT, as for
            // a SUBSCRIBE, logs nothing above DEBUG itself. That is not true of every frame: a refused
            // SEND, and a frame it cannot decode, spring-websocket 7.0.9's StompSubProtocolHandler logs
            // at ERROR, which no interceptor can prevent (S5.C4).
            log.debug("Refused a STOMP CONNECT: no bearer token");
            // Audited only when something was presented. A tab whose session could not be renewed
            // reconnects with no header at all every five seconds, and that is no more an attempt than a
            // refresh with no cookie: an entry each time would bury the refusals worth reading.
            if (authorization != null) {
                auditTrail.record(REFUSED_CONNECT);
            }
            throw new IllegalArgumentException("Missing or malformed Authorization header on STOMP CONNECT");
        }
        BearerAuthentication authenticated = authenticateBearer(authorization.substring(BEARER_PREFIX.length()))
                .orElseThrow(() -> {
                    log.debug("Refused a STOMP CONNECT: the token is not usable");
                    auditTrail.record(REFUSED_CONNECT);
                    return new IllegalArgumentException("Unusable token on STOMP CONNECT");
                });
        UUID userId = authenticated.user().getId();
        auditTrail.record(AuditEvent.allowed(AuditAction.AUTH_CONNECT, userId, userId));
        return new StompPrincipal(userId.toString(), authenticated.sessionId());
    }

    /**
     * Authenticates the token, auditing a failure of the authenticator itself as the fault it is.
     *
     * <p>The authenticator reads the session and the account on every call, so an exception from it is an
     * outage rather than a refused token, and recording it as a refusal would hide it in ordinary traffic.
     */
    private Optional<BearerAuthentication> authenticateBearer(String token) {
        try {
            return authenticator.authenticateWithSession(token);
        } catch (RuntimeException thrown) {
            auditTrail.record(AuditEvent.from(AuditAction.AUTH_CONNECT, null, null, thrown));
            throw thrown;
        }
    }

    private void authoriseSubscription(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            refuse("a SUBSCRIBE from a session that never connected");
        }
        if (!ALLOWED_SUBSCRIPTION.equals(accessor.getDestination())) {
            refuse("a SUBSCRIBE to a destination this application does not publish to");
        }
        Map<String, Object> socket = accessor.getSessionAttributes();
        String id = accessor.getSubscriptionId();
        if (socket == null || id == null) {
            // Without the socket's attributes there is nowhere to count what it holds, and STOMP requires
            // the id. Neither happens over a real WebSocket; refusing keeps the limit closed if it did.
            refuse("a SUBSCRIBE with no socket or no subscription id");
        }
        if (socket.putIfAbsent(HELD_SUBSCRIPTION, id) != null) {
            refuse("a second SUBSCRIBE on a socket that already holds one");
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
