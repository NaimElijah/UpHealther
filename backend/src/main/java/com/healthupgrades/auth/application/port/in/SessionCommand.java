package com.healthupgrades.auth.application.port.in;

import com.healthupgrades.auth.application.RefreshOutcome;
import com.healthupgrades.auth.application.SessionGrant;

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
     *         here is not rolled back
     * @throws RuntimeException only when the session store fails, after the failure has been audited
     */
    RefreshOutcome refresh(String presentedCredential);

    /**
     * Ends the one session a credential belongs to, leaving the account signed in on its other devices,
     * and audits the attempt whatever its outcome.
     *
     * @param presentedCredential the raw cookie value. A request that carried no cookie has presented
     *                            nothing, and is answered without calling this
     * @return whether a session was actually ended. The caller is answered the same either way
     * @throws RuntimeException only when the session store fails, after the failure has been audited
     */
    boolean revoke(String presentedCredential);

    /**
     * Ends every session an account holds.
     *
     * @param userId the account
     * @return how many sessions were still live
     */
    int revokeAllForUser(UUID userId);
}
