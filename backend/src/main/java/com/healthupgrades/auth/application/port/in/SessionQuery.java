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
     * Whether a session may still be used by a given account.
     *
     * <p>There is no form that leaves the account out: a live session is only ever live for its owner.
     *
     * @param sessionId the session named by an access token's {@code sid} claim
     * @param userId    the account named by the same token's {@code sub} claim
     * @return false when the session is unknown, revoked, idled out, past its absolute cap, or another
     *         account's
     */
    boolean isActive(UUID sessionId, UUID userId);
}
