package com.healthupgrades.auth.domain.port.out;

import java.time.Instant;

/**
 * An access token as {@link AccessTokenIssuerPort} issues it: the value to hand the client, and when it
 * lapses.
 *
 * <p>The expiry travels with the token so a client can be told when to renew without decoding a token
 * it has no business parsing.
 *
 * @param value     the token, opaque to this context
 * @param expiresAt the instant it stops being accepted
 */
public record AccessToken(String value, Instant expiresAt) {

    /**
     * Prints only when the token lapses. A record's generated {@code toString} would print the bearer
     * token, which is the whole credential and must never reach a log line (NFR-6).
     */
    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + "]";
    }
}
