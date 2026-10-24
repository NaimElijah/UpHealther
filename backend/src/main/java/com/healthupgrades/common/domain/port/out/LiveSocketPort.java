package com.healthupgrades.common.domain.port.out;

import java.util.UUID;

/**
 * Outbound port closing the live notification sockets that a session, or a whole account, holds open.
 *
 * <p>A socket is authenticated once, by its CONNECT frame, and sends nothing afterwards that could be
 * checked. So ending a session stops its access token on the next HTTP request, but a socket it opened
 * would go on receiving the account's notifications until it next reconnected (#83). The {@code auth}
 * context ends sessions and calls this; the STOMP adapter in {@code common.websocket} knows the sockets
 * and implements it (ADR-054).
 *
 * <p>Both calls are best effort and never throw: they run after the session's end has committed, when a
 * failure could no longer undo it, and a socket that cannot be reached is one that is already closing.
 */
public interface LiveSocketPort {

    /**
     * Closes every socket opened with an access token issued within one session, and no other.
     *
     * @param sessionId the server-side session that ended
     */
    void closeForSession(UUID sessionId);

    /**
     * Closes every socket the account holds, whichever session opened it.
     *
     * @param userId the account whose sessions all ended
     */
    void closeForUser(UUID userId);
}
