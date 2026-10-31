package com.healthupgrades.auth.adapter.in.web;

import java.time.Instant;

/**
 * Web response for a successful authentication: the issued access token, when it lapses, and the
 * authenticated user's public view.
 *
 * <p>Named {@code accessToken} rather than {@code token} because there are now two credentials and
 * only one of them is in this body. The other is the refresh credential, which is in a cookie the
 * client cannot read and must not try to.
 *
 * <p>{@code expiresAt} is here so a client can tell a token about to lapse from one that is not. The
 * SPA uses it in one place: the notification socket renews before it connects with a token that would
 * lapse, because a socket is authenticated once. Its HTTP requests do not; they renew after a 401, and
 * retry the request that was refused.
 *
 * @param accessToken the short-lived bearer token, for the client to hold in memory
 * @param expiresAt   when it stops being accepted
 * @param user        the authenticated account
 */
public record TokenPair(String accessToken, Instant expiresAt, UserDto user) {

    /**
     * Prints only when the token lapses. Spring MVC writes a response body's {@code toString} at DEBUG,
     * and the generated one would print the bearer token and the account's name and address (NFR-6).
     */
    @Override
    public String toString() {
        return "TokenPair[expiresAt=" + expiresAt + "]";
    }
}
