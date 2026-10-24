package com.healthupgrades.common.security;

import java.util.Objects;
import java.util.UUID;

/**
 * A bearer token that authenticated: the account it names, and the session it was issued within.
 *
 * <p>The HTTP filter needs only the account and asks {@link BearerTokenAuthenticator#authenticate}. The
 * STOMP interceptor asks for this as well, because a socket outlives the frame that opened it, and has to
 * be found again when that session ends.
 *
 * @param user      the account, with no password hash on it
 * @param sessionId the session the token's {@code sid} claim names, live when the token was checked
 */
public record BearerAuthentication(SecurityUser user, UUID sessionId) {

    /** @throws NullPointerException if either component is null */
    public BearerAuthentication {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
