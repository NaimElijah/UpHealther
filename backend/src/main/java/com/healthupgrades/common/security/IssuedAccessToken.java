package com.healthupgrades.common.security;

import java.time.Instant;

/**
 * An access token as issued: the compact JWT and the instant it stops being accepted.
 *
 * <p>The expiry travels with the token so a client can be told when it lapses without decoding a JWT it
 * has no business parsing.
 *
 * @param value     the compact, signed JWT
 * @param expiresAt the token's {@code exp}
 */
public record IssuedAccessToken(String value, Instant expiresAt) {

    /**
     * Prints only when the token lapses. A record's generated {@code toString} would print the JWT, which
     * is the whole credential and must never reach a log line (NFR-6).
     */
    @Override
    public String toString() {
        return "IssuedAccessToken[expiresAt=" + expiresAt + "]";
    }
}
