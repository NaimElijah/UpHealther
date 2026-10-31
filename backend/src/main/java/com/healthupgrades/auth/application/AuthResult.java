package com.healthupgrades.auth.application;

import com.healthupgrades.auth.application.port.in.SessionGrant;
import com.healthupgrades.user.domain.model.User; // the authenticated domain user
import java.time.Instant;
import java.util.UUID;

/**
 * Result of an authentication use case: the issued access token, the authenticated {@link User}, and
 * the session the token belongs to. The web adapter maps this to the HTTP response — the first two
 * into the body, the third into a {@code Set-Cookie} header.
 *
 * @param token     the access token, for the client to hold in memory
 * @param expiresAt when that token stops being accepted, which the web adapter returns beside it
 * @param user      the authenticated account
 * @param session   the session and its refresh credential. The raw credential is in this record and
 *                  nowhere else that outlives the request
 */
public record AuthResult(String token, Instant expiresAt, User user, SessionGrant session) {

    /**
     * Prints the account's id and the token's expiry. The generated {@code toString} would print the
     * access token and, through the grant, the refresh credential (NFR-6).
     */
    @Override
    public String toString() {
        UUID userId = user == null ? null : user.getId();
        return "AuthResult[userId=" + userId + ", expiresAt=" + expiresAt + ", session=" + session + "]";
    }
}
