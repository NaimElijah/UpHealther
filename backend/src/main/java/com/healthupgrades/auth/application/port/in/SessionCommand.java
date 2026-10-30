package com.healthupgrades.auth.application.port.in;

import java.util.UUID;

/**
 * Inbound write port for sessions: opening one, exchanging its credential, and ending it.
 */
public interface SessionCommand {

    /**
     * Opens a session for an account that has just proved who it is.
     *
     * @param userId the authenticated account
     * @return the session and the credential to hand the client; the raw credential exists nowhere else
     */
    SessionGrant open(UUID userId);

    /**
     * Exchanges a refresh credential for a new one, and audits the attempt whatever its outcome.
     *
     * @param presentedCredential the raw cookie value, entirely unvalidated. A request that carried no
     *                            cookie has presented nothing, and is answered without calling this
     * @return what happened. A refusal is an outcome rather than an exception, so a revocation decided
     *         here is not rolled back, and the sockets the revoked session opened are closed once it
     *         commits (ADR-054)
     * @throws RuntimeException when something it depends on fails. A failure inside the transaction is
     *         audited as FAILED before it is rethrown; one that stops the transaction from starting never
     *         reaches this method and is not audited (#133)
     */
    RefreshOutcome refresh(String presentedCredential);

    /**
     * Ends the one session a credential belongs to, leaving the account signed in on its other devices,
     * and audits the attempt whatever its outcome. Once the end commits, the notification sockets the
     * session opened are closed too (ADR-054).
     *
     * @param presentedCredential the raw cookie value. A request that carried no cookie has presented
     *                            nothing, and is answered without calling this
     * @return whether the credential was accepted, which leaves its session ended — true as well for a
     *         session that had already lapsed or been revoked. A credential the session spent, presented
     *         after its grace window, is not accepted: it is reuse, and ends the session as reuse, exactly
     *         as {@link #refresh} does. The caller is answered the same either way
     * @throws RuntimeException when something it depends on fails. A failure inside the transaction is
     *         audited as FAILED before it is rethrown; one that stops the transaction from starting never
     *         reaches this method and is not audited (#133)
     */
    boolean revoke(String presentedCredential);

    /**
     * Ends every session an account holds, and once that commits, closes every notification socket the
     * account holds, whichever session opened it (ADR-054).
     *
     * @param userId the account
     * @return how many sessions were still live
     */
    int revokeAllForUser(UUID userId);
}
