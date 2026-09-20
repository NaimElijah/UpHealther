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
     * Exchanges a refresh credential for a new one.
     *
     * @param presentedCredential the raw cookie value, entirely unvalidated
     * @return what happened. Never throws, so a revocation decided here is not rolled back
     */
    RefreshOutcome refresh(String presentedCredential);

    /**
     * Ends the one session a credential belongs to, leaving the account signed in on its other devices.
     *
     * @param presentedCredential the raw cookie value
     * @return whether a session was actually ended — for the audit trail rather than the caller, which
     *         is answered the same either way
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
