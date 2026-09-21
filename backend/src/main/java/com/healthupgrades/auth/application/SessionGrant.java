package com.healthupgrades.auth.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A session and the credential that proves possession of it — what a caller needs in order to set a
 * cookie.
 *
 * <p>The raw credential appears here and nowhere else that outlives the request: the database holds
 * only its digest, and this record exists to carry the value from the one moment it is generated to the
 * one moment it is written into a {@code Set-Cookie} header.
 *
 * @param sessionId    the session, which also goes into the access token's {@code sid} claim
 * @param refreshToken the credential to put in the cookie, in its {@code sessionId.secret} form
 * @param expiresAt    the session's absolute cap, which the cookie's {@code Max-Age} mirrors so a
 *                     browser discards it at the same moment the server stops honouring it
 */
public record SessionGrant(UUID sessionId, String refreshToken, Instant expiresAt) {
}
