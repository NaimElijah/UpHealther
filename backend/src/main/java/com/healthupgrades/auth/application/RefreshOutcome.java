package com.healthupgrades.auth.application;

import java.util.UUID;

/**
 * How an attempt to exchange a refresh credential ended.
 *
 * <p>A returned value rather than an exception, and the distinction is load-bearing. Refresh runs in a
 * transaction, and detecting reuse <em>revokes the session</em> — throwing from inside that transaction
 * would roll the revocation back, so the one case where it matters most is the one case where it would
 * silently not happen. The service decides, the transaction commits, and the controller turns the
 * verdict into a status.
 *
 * <p>Sealed, so adding a fourth verdict fails to compile everywhere that maps them rather than falling
 * into an {@code else} that answers 401 for something new.
 */
public sealed interface RefreshOutcome {

    /**
     * The credential was the current one: the session continues, under a new credential.
     *
     * @param userId the account the session belongs to, so a fresh access token can be issued for it
     * @param grant  the new credential and the session it belongs to
     */
    record Rotated(UUID userId, SessionGrant grant) implements RefreshOutcome {
    }

    /**
     * The credential was rotated out moments ago, inside the grace window. Worth retrying, and nothing
     * has been revoked.
     *
     * <p>This is what two tabs waking together looks like, or a request retried after a dropped
     * connection: the one that arrives second holds a credential that was still current when it was
     * sent. Treating that as theft would sign people out for having a second tab open.
     */
    record Stale() implements RefreshOutcome {
    }

    /**
     * The credential is not usable, and the caller should sign in again.
     *
     * <p>One verdict covers every reason — unparseable, unknown session, expired, revoked, replayed
     * after the grace window, or simply wrong. Telling an anonymous caller which of those it was would
     * answer questions it has no business asking, and the client does the same thing in every case.
     */
    record Rejected() implements RefreshOutcome {
    }
}
