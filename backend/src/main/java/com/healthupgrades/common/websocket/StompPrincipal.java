package com.healthupgrades.common.websocket;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * STOMP session principal whose name is the user's id (as a string). Using the userId as the principal
 * name lets {@code SimpMessagingTemplate.convertAndSendToUser(userId, ...)} route messages without an
 * email lookup, and lets the client subscribe to {@code /user/queue/...} without knowing its own name.
 *
 * <p>It also keeps the session the socket was opened in. A socket is checked only when it connects, so
 * the session is what lets ending that session find the socket again. Sign-out is per device, so it
 * cannot be found by account alone.
 *
 * @param name      the user's id in string form
 * @param sessionId the server-side session whose access token opened the socket
 */
public record StompPrincipal(String name, UUID sessionId) implements Principal {

    /** @throws NullPointerException if either component is null */
    public StompPrincipal {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return name;
    }
}
