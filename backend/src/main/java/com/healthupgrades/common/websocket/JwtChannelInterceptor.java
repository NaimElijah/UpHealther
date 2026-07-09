package com.healthupgrades.common.websocket;

import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP connections. On the CONNECT frame it validates the JWT supplied in the
 * "Authorization: Bearer ..." header and attaches a {@link StompPrincipal} (named by userId) to the
 * session, so subsequent per-user messaging is routed correctly. An invalid/missing token rejects the
 * connection.
 */
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final UserQuery userQuery;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing or malformed Authorization header on STOMP CONNECT");
            }
            String token = authHeader.substring(7);
            if (!tokenProvider.validateToken(token)) {
                throw new IllegalArgumentException("Invalid JWT on STOMP CONNECT");
            }
            String email = tokenProvider.extractEmail(token);
            User user = userQuery.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user on STOMP CONNECT"));
            accessor.setUser(new StompPrincipal(user.getId().toString()));
        }
        return message;
    }
}
