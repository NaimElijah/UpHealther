package com.healthupgrades.auth.application.port.in;

import java.util.UUID;

/**
 * Inbound read port for session state.
 *
 * <p>Kept apart from {@code SessionCommand} because it has a different caller with a different
 * appetite: this one is consulted on every authenticated request, through an inverted port, by code
 * that must not be able to change anything.
 */
public interface SessionQuery {

    /**
     * Whether a session may still be used.
     *
     * @param sessionId the session named by an access token's {@code sid} claim
     * @return false when it is unknown, revoked, idled out, or past its absolute cap
     */
    boolean isActive(UUID sessionId);
}
