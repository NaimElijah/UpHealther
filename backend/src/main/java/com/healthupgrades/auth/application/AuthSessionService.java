package com.healthupgrades.auth.application;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.auth.application.port.in.RefreshOutcome;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.auth.application.port.in.SessionGrant;
import com.healthupgrades.auth.application.port.in.SessionQuery;
import com.healthupgrades.auth.domain.event.AuthSessionEnded;
import com.healthupgrades.auth.domain.event.UserSessionsEnded;
import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.model.RefreshToken;
import com.healthupgrades.auth.domain.model.SpentCredential;
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort;
import com.healthupgrades.auth.domain.port.out.SecretGeneratorPort;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for server-side sessions: opening one, rotating its credential, and ending it.
 *
 * <p>Separate from {@code AuthService} on purpose, and not merely for size. {@code AuthService} holds
 * the {@code AuthenticationManager}, which Spring Security builds from {@code SecurityConfig}, which
 * builds the filter chain, which consults session state on every request. Folding sessions into
 * {@code AuthService} closes that loop and the context fails to start with a bean cycle. This class
 * depends on no security bean at all, which is what keeps the loop open.
 *
 * <p>The rules it enforces:
 *
 * <ul>
 *   <li><strong>One use per credential.</strong> Every refresh mints a new secret and remembers the
 *       digest of the one it replaced, and of every one before it, so a stolen credential is good for at
 *       most one exchange.</li>
 *   <li><strong>Reuse ends the session.</strong> Presenting a spent credential after the grace window,
 *       however many rotations old, means the legitimate client already exchanged it — so whoever is
 *       presenting it now is not that client, and the session is revoked for both of them. Signing the
 *       real user out is the point: it is the only available signal that something is wrong.</li>
 *   <li><strong>An unrecognised credential revokes nothing.</strong> If it is neither the current
 *       credential nor a spent one, this is not a credential the session ever issued. Revoking here
 *       would let anyone who learned a session id sign its owner out by guessing at the secret.</li>
 *   <li><strong>Every attempt is audited, and a refusal names only what it can prove</strong> (NFR-23).
 *       It names the session whenever the session exists, and the session's owner only when the
 *       presented credential is one the session issued. The session id is no secret, so a refusal of a
 *       credential the session never issued says nothing about who sent it, and naming the owner would
 *       let anybody holding the id put attempts against their name. Naming the owner says whose
 *       credential it was, not who sent it: a superseded one presented after the grace window has
 *       probably been stolen, and is named exactly as the reuse entry names it.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthSessionService implements SessionQuery, SessionCommand {

    private final AuthSessionRepositoryPort sessions; // outbound persistence port
    private final SecretGeneratorPort secrets; // outbound randomness port
    private final AuthSessionProperties properties; // validated app.auth.session settings
    private final AuditTrail auditTrail; // records every refresh and sign-out, and detected reuse
    private final DomainEventPublisher events; // announces a session's end, so its open sockets close too
    private final Clock clock; // injectable, so expiry is testable without waiting

    /** {@inheritDoc} */
    @Override
    @Transactional
    public SessionGrant open(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        RefreshToken token = new RefreshToken(sessionId, secrets.generate());
        AuthSession session = AuthSession.opened(
                sessionId, userId, token.hash(), clock.instant(), properties.idle(), properties.absolute());
        sessions.save(session);
        return new SessionGrant(sessionId, token.value(), session.getAbsoluteExpiresAt());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the session under a write lock, so two refreshes of one session serialise rather than
     * both rotating from the same starting state and leaving one client holding a credential the row no
     * longer accepts.
     */
    @Override
    @Transactional
    public RefreshOutcome refresh(String presentedCredential) {
        try {
            return exchange(presentedCredential);
        } catch (RuntimeException thrown) {
            // A fault, not a refusal - the distinction AuthService.login draws for a sign-in. Nothing
            // about the attempt is known for certain once the store has failed, so nothing is named.
            // Only a failure inside the transaction gets here: one that stops it from starting is
            // thrown by the proxy before this method runs, and is not audited (#133).
            auditTrail.record(AuditEvent.from(AuditAction.AUTH_REFRESH, null, null, thrown));
            throw thrown;
        }
    }

    private RefreshOutcome exchange(String presentedCredential) {
        Optional<RefreshToken> parsed = RefreshToken.parse(presentedCredential);
        if (parsed.isEmpty()) {
            recordRefusalOfNothing(AuditAction.AUTH_REFRESH);
            return new RefreshOutcome.Rejected();
        }
        RefreshToken presented = parsed.get();
        Optional<AuthSession> found = sessions.findForUpdate(presented.sessionId());
        if (found.isEmpty()) {
            recordRefusalOfNothing(AuditAction.AUTH_REFRESH);
            return new RefreshOutcome.Rejected();
        }

        AuthSession session = found.get();
        Instant now = clock.instant();
        byte[] presentedHash = presented.hash();

        if (!session.isActive(now)) {
            recordRefusal(AuditAction.AUTH_REFRESH, session, issuedHere(session, presentedHash));
            return new RefreshOutcome.Rejected();
        }
        if (session.matchesCurrent(presentedHash)) {
            RefreshToken rotated = new RefreshToken(session.getId(), secrets.generate());
            SpentCredential retired = session.rotate(rotated.hash(), now, properties.idle());
            sessions.save(session);
            sessions.recordSpent(session.getId(), retired);
            auditTrail.record(AuditEvent.allowed(AuditAction.AUTH_REFRESH, session.getUserId(), session.getId()));
            return new RefreshOutcome.Rotated(
                    session.getUserId(),
                    new SessionGrant(session.getId(), rotated.value(), session.getAbsoluteExpiresAt()));
        }
        Optional<SpentCredential> spent = sessions.findSpent(session.getId(), presentedHash);
        if (spent.isEmpty()) {
            recordRefusal(AuditAction.AUTH_REFRESH, session, false);
            return new RefreshOutcome.Rejected();
        }
        if (spent.get().isWithinGrace(now, properties.rotationGrace())) {
            recordRefusal(AuditAction.AUTH_REFRESH, session, true);
            return new RefreshOutcome.Stale();
        }
        revokeForReuse(session);
        return new RefreshOutcome.Rejected();
    }

    /**
     * Ends a session whose spent credential was presented after the grace window: two parties hold its
     * credentials, and the one presenting this is no longer the legitimate client. Refresh and sign-out
     * both come here, so a replay is handled the same whichever endpoint it is presented to.
     */
    private void revokeForReuse(AuthSession session) {
        session.revoke();
        sessions.save(session);
        // WARN, and the only line in this class at that level: a spent credential presented after the
        // grace window means two parties hold one session's credentials. Ids only — which session and
        // whose — because that is what an investigation needs and everything else about it is personal
        // data (NFR-6). The credential is never logged in any form.
        log.warn(
                "A superseded refresh credential was replayed; the session has been revoked {} {}",
                keyValue("sessionId", session.getId()),
                keyValue("userId", session.getUserId()));
        auditTrail.record(new AuditEvent(
                AuditAction.AUTH_TOKEN_REUSE, session.getUserId(), session.getId(), AuditOutcome.REFUSED));
        publishEnd(session);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean revoke(String presentedCredential) {
        try {
            return end(presentedCredential);
        } catch (RuntimeException thrown) {
            // As in refresh: a fault, recorded as one, naming nothing it cannot be sure of.
            auditTrail.record(AuditEvent.from(AuditAction.AUTH_LOGOUT, null, null, thrown));
            throw thrown;
        }
    }

    private boolean end(String presentedCredential) {
        Optional<RefreshToken> parsed = RefreshToken.parse(presentedCredential);
        if (parsed.isEmpty()) {
            recordRefusalOfNothing(AuditAction.AUTH_LOGOUT);
            return false;
        }
        RefreshToken presented = parsed.get();
        Optional<AuthSession> found = sessions.findForUpdate(presented.sessionId());
        if (found.isEmpty()) {
            recordRefusalOfNothing(AuditAction.AUTH_LOGOUT);
            return false;
        }

        AuthSession session = found.get();
        byte[] presentedHash = presented.hash();
        // The credential has to match, not merely name the session. Knowing a session id is not
        // authority to end it, and the id is the half of the credential that travels in a token claim.
        //
        // A spent digest signs out only inside the rotation grace window. After it, it is reuse here
        // exactly as it is at /refresh: the session is revoked, logged WARN and audited AUTH_TOKEN_REUSE.
        // Signing out with it as an ordinary sign-out would hide the reuse, and refusing it would leave
        // the session to whoever rotated it - while the caller, answered 204 either way, believes it
        // ended (S5.A4).
        if (!session.matchesCurrent(presentedHash)) {
            Optional<SpentCredential> spent = sessions.findSpent(session.getId(), presentedHash);
            if (spent.isEmpty()) {
                recordRefusal(AuditAction.AUTH_LOGOUT, session, false);
                return false;
            }
            if (!spent.get().isWithinGrace(clock.instant(), properties.rotationGrace())) {
                revokeForReuse(session);
                return false;
            }
        }
        session.revoke();
        sessions.save(session);
        auditTrail.record(AuditEvent.allowed(AuditAction.AUTH_LOGOUT, session.getUserId(), session.getId()));
        publishEnd(session);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Announces the end once for the account whether or not any session was live: a socket outlives
     * the session that opened it, so one may still be open on a session that had already expired.
     */
    @Override
    @Transactional
    public int revokeAllForUser(UUID userId) {
        int revoked = sessions.revokeAllForUser(userId);
        events.publish(new UserSessionsEnded(userId, LocalDateTime.now(clock)));
        return revoked;
    }

    /**
     * Announces that a session has ended, so the sockets it opened are closed (ADR-054). A socket is
     * authenticated once, when it connects, and sends nothing afterwards that could be checked, so
     * without this it would go on receiving the account's notifications until it next reconnected.
     */
    private void publishEnd(AuthSession session) {
        events.publish(new AuthSessionEnded(session.getId(), session.getUserId(), LocalDateTime.now(clock)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>No transaction of its own: this is one read, on the path of every authenticated request, and
     * the repository already reads within one.
     */
    @Override
    public boolean isActive(UUID sessionId, UUID userId) {
        return sessions.findById(sessionId)
                .filter(session -> session.getUserId().equals(userId))
                .map(session -> session.isActive(clock.instant()))
                .orElse(false);
    }

    /** Records a refusal of a credential that named no session at all, and so names nobody. */
    private void recordRefusalOfNothing(AuditAction action) {
        auditTrail.record(new AuditEvent(action, null, null, AuditOutcome.REFUSED));
    }

    /** Whether the presented credential is one this session issued, current or spent. */
    private boolean issuedHere(AuthSession session, byte[] presentedHash) {
        return session.matchesCurrent(presentedHash)
                || sessions.findSpent(session.getId(), presentedHash).isPresent();
    }

    /**
     * Records a refusal against an existing session, naming its owner only when the presented credential
     * is one the session issued, current or spent - see the class comment for why.
     */
    private void recordRefusal(AuditAction action, AuthSession session, boolean issuedHere) {
        auditTrail.record(
                new AuditEvent(action, issuedHere ? session.getUserId() : null, session.getId(), AuditOutcome.REFUSED));
    }

    /**
     * Removes sessions nothing can use again.
     *
     * <p>Not on {@link SessionCommand}: housekeeping is not a use case, and putting it on the port would
     * offer every caller a way to delete rows wholesale.
     *
     * @return how many rows were removed
     */
    @Transactional
    public int deleteUnusableSessions() {
        return sessions.deleteUnusableAsOf(clock.instant());
    }
}
