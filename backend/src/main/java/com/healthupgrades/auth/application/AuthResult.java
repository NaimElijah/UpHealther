package com.healthupgrades.auth.application;

import com.healthupgrades.user.domain.model.User; // the authenticated domain user

/**
 * Result of an authentication use case: the issued access token, the authenticated {@link User}, and
 * the session the token belongs to. The web adapter maps this to the HTTP response — the first two
 * into the body, the third into a {@code Set-Cookie} header.
 *
 * @param token   the access token, for the client to hold in memory
 * @param user    the authenticated account
 * @param session the session and its refresh credential. The raw credential is in this record and
 *                nowhere else that outlives the request
 */
public record AuthResult(String token, User user, SessionGrant session) {}
