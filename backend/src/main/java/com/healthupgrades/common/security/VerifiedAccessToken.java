package com.healthupgrades.common.security;

import java.util.UUID;

/**
 * What a verified access token asserts: which account it was issued for.
 *
 * <p>Deliberately nothing about what that account may do. Roles and account state are read from the
 * database on every request, so a change to either takes effect on the next request rather than when the
 * token lapses.
 *
 * <p>It does assert which <em>session</em> issued it, because that is the one thing a token cannot be
 * asked to carry state about: a session can be ended, and an access token that named no session
 * would go on working until it expired however thoroughly its session had been revoked.
 *
 * @param userId    the account the token was issued for, from its {@code sub} claim
 * @param sessionId the session it was issued within, from its {@code sid} claim
 */
public record VerifiedAccessToken(UUID userId, UUID sessionId) {
}
