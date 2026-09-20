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
 * Authenticates STOMP connections. On the CONNECT frame it checks the bearer token supplied in the
 * {@code Authorization} native header, through the same {@link BearerTokenAuthenticator} the HTTP filter
 * uses, and attaches a {@link StompPrincipal} named by user id to the session, so per-user messaging is
 * routed to the right account. A missing or unusable token refuses the connection.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final BearerTokenAuthenticator authenticator;

    /**
     * Authenticates a CONNECT frame and lets every other frame pass through untouched.
     *
     * @param message the inbound STOMP message
     * @param channel the client inbound channel
     * @return the same message, with the session principal attached on CONNECT
     * @throws IllegalArgumentException if a CONNECT frame carries no bearer token or an unusable one:
     *                                  Spring turns this into a refused connection, which is the only way
     *                                  to reject a STOMP session from an interceptor
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(authenticate(accessor));
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
}
