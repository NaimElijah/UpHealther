package com.healthupgrades.auth.adapter.in.event;

import com.healthupgrades.auth.domain.event.AuthSessionEnded;
import com.healthupgrades.auth.domain.event.UserSessionsEnded;
import com.healthupgrades.common.domain.port.out.LiveSocketPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Closes the sockets a session opened once its end has committed (ADR-054).
 *
 * <p>After the commit, not before it. A socket closed while the revocation was still uncommitted
 * reconnects five seconds later, and if the commit had not landed by then, that CONNECT would read the
 * session as live and stay open for good. On a rollback nothing has ended, so nothing is closed.
 *
 * <p>Writes nothing, so it needs no transaction of its own: ADR-028's {@code REQUIRES_NEW} rule is for
 * listeners that write.
 */
@Component
@RequiredArgsConstructor
public class SessionEndSocketListener {

    private final LiveSocketPort sockets; // inverted: common.websocket knows the sockets

    /** One session was signed out of, or revoked for reuse: only its own sockets close. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionEnded(AuthSessionEnded event) {
        sockets.closeForSession(event.sessionId());
    }

    /** Every session of the account ended, as disabling it does: every socket it holds closes. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserSessionsEnded(UserSessionsEnded event) {
        sockets.closeForUser(event.userId());
    }
}
