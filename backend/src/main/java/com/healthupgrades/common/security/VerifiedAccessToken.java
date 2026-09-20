package com.healthupgrades.common.security;

import java.util.UUID;

/**
 * What a verified access token asserts: which account it was issued for.
 *
 * <p>Deliberately nothing about what that account may do. Roles and account state are read from the
 * database on every request, so a change to either takes effect on the next request rather than when the
 * token lapses.
 *
 * @param userId the account the token was issued for, from its {@code sub} claim
 */
public record VerifiedAccessToken(UUID userId) {
}
