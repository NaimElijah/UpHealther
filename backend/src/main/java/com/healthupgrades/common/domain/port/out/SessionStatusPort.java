package com.healthupgrades.common.domain.port.out;

import java.util.UUID;

/**
 * Outbound port asking whether a session is still usable.
 *
 * <p>An inverted dependency, and the inversion is the point. The security adapter authenticates every
 * request and must know whether the session an access token names has been ended; the {@code auth}
 * context is what knows. Having {@code common} call into {@code auth} would put an arrow from the
 * shared kernel into a bounded context, which every other context already depends on — a cycle by
 * construction. So {@code common} declares what it needs here, and
 * {@code auth.adapter.in.composition.SessionStatusAdapter} supplies it. The same shape as
 * {@code UpgradeTrackingSummaryPort}, for the same reason.
 *
 * <p>Deliberately one boolean. The security adapter has no business knowing when a session was opened,
 * how often it has been refreshed, or why it ended, and a richer answer would invite it to care.
 *
 * <p>The owner is part of the question, not left for the caller to compare. A session id is no secret
 * and neither is an account id, so a session asked about alone would let a token pairing one account
 * with another's live session authenticate as the first. The server never issues that pair; only a
 * leaked signing key can mint it, and the key alone must not be enough (#286).
 */
public interface SessionStatusPort {

    /**
     * Whether a session may still be used by the account a token names.
     *
     * @param sessionId the session named by an access token's {@code sid} claim
     * @param userId    the account named by the same token's {@code sub} claim
     * @return false when the session is unknown, revoked, idled out, past its absolute cap, or another
     *         account's
     */
    boolean isActive(UUID sessionId, UUID userId);
}
