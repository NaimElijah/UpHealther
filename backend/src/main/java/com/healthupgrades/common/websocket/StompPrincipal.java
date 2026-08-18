package com.healthupgrades.common.websocket;

import java.security.Principal;

/**
 * STOMP session principal whose name is the user's id (as a string). Using the userId as the principal
 * name lets {@code SimpMessagingTemplate.convertAndSendToUser(userId, ...)} route messages without an
 * email lookup, and lets the client subscribe to {@code /user/queue/...} without knowing its own name.
 *
 * @param name the user's id in string form
 */
public record StompPrincipal(String name) implements Principal {

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return name;
    }
}
