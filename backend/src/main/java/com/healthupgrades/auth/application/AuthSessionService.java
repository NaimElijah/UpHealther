package com.healthupgrades.auth.application;

import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.auth.application.port.in.SessionQuery;
import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.model.RefreshToken;
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort;
import com.healthupgrades.auth.domain.port.out.SecretGeneratorPort;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

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
 *       digest of the one it replaced, so a stolen credential is good for at most one exchange.</li>
 *   <li><strong>Reuse ends the session.</strong> Presenting a superseded credential after the grace
 *       window means the legitimate client already exchanged it — so whoever is presenting it now is
 *       not that client, and the session is revoked for both of them. Signing the real user out is the
 *       point: it is the only available signal that something is wrong.</li>
 *   <li><strong>An unrecognised credential revokes nothing.</strong> If neither digest matches, this is
 *       not a credential the session ever issued. Revoking here would let anyone who learned a session
 *       id sign its owner out by guessing at the secret.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthSessionService implements SessionQuery, SessionCommand {

    private final AuthSessionRepositoryPort sessions; // outbound persistence port
    private final SecretGeneratorPort secrets; // outbound randomness port
    private final AuthSessionProperties properties; // validated app.auth.session settings
    private final AuditTrail auditTrail; // records refreshes, sign-outs and detected reuse
    private final Clock clock; // injectable, so expiry is testable without waiting

    /** {@inheritDoc} */
    @Override
    @Transactional
    public SessionGrant open(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        RefreshToken token = new RefreshToken(sessionId, secrets.generate());
        AuthSession session = AuthSession.opened(sessionId, userId, token.hash(), clock.instant(),
                properties.idle(), properties.absolute());
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
        Optional<RefreshToken> parsed = RefreshToken.parse(presentedCredential);
        if (parsed.isEmpty()) {
            return new RefreshOutcome.Rejected();
        }
        RefreshToken presented = parsed.get();
        Optional<AuthSession> found = sessions.findForUpdate(presented.sessionId());
        if (found.isEmpty()) {
            return new RefreshOutcome.Rejected();
        }

        AuthSession session = found.get();
        Instant now = clock.instant();
        byte[] presentedHash = presented.hash();

        if (!session.isActive(now)) {
            return new RefreshOutcome.Rejected();
        }
        if (session.matchesCurrent(presentedHash)) {
            RefreshToken rotated = new RefreshToken(session.getId(), secrets.generate());
            session.rotate(rotated.hash(), now, properties.idle());
            sessions.save(session);
            auditTrail.record(AuditEvent.allowed(AuditAction.AUTH_REFRESH, session.getUserId(), session.getId()));
            return new RefreshOutcome.Rotated(session.getUserId(),
                    new SessionGrant(session.getId(), rotated.value(), session.getAbsoluteExpiresAt()));
        }
        if (session.matchesPreviousWithin(presentedHash, now, properties.rotationGrace())) {
            return new RefreshOutcome.Stale();
        }
        if (session.matchesPrevious(presentedHash)) {
            session.revoke();
            sessions.save(session);
            // WARN, and the only line in this class at that level: a superseded credential presented
            // after the grace window means two parties hold one session's credentials. Ids only — which
            // session and whose — because that is what an investigation needs and everything else about
            // it is personal data (NFR-6). The credential is never logged in any form.
            log.warn("A superseded refresh credential was replayed; the session has been revoked {} {}",
                    keyValue("sessionId", session.getId()), keyValue("userId", session.getUserId()));
            auditTrail.record(new AuditEvent(AuditAction.AUTH_TOKEN_REUSE, session.getUserId(),
                    session.getId(), AuditOutcome.REFUSED));
            return new RefreshOutcome.Rejected();
        }
        return new RefreshOutcome.Rejected();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean revoke(String presentedCredential) {
        Optional<RefreshToken> parsed = RefreshToken.parse(presentedCredential);
        if (parsed.isEmpty()) {
            return false;
        }
        RefreshToken presented = parsed.get();
        Optional<AuthSession> found = sessions.findForUpdate(presented.sessionId());
        if (found.isEmpty()) {
            return false;
        }

        AuthSession session = found.get();
        byte[] presentedHash = presented.hash();
        // The credential has to match, not merely name the session. Knowing a session id is not
        // authority to end it, and the id is the half of the credential that travels in a token claim.
        // The superseded digest counts too: signing out from a tab that has not refreshed since the
        // other one did must still work.
        if (!session.matchesCurrent(presentedHash) && !session.matchesPrevious(presentedHash)) {
            return false;
        }
        session.revoke();
        sessions.save(session);
        auditTrail.record(AuditEvent.allowed(AuditAction.AUTH_LOGOUT, session.getUserId(), session.getId()));
        return true;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public int revokeAllForUser(UUID userId) {
        return sessions.revokeAllForUser(userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>No transaction of its own: this is one read, on the path of every authenticated request, and
     * the repository already reads within one.
     */
    @Override
    public boolean isActive(UUID sessionId) {
        return sessions.findById(sessionId)
                .map(session -> session.isActive(clock.instant()))
                .orElse(false);
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
