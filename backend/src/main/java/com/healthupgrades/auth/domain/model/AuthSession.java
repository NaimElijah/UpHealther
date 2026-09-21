package com.healthupgrades.auth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A signed-in session, held server-side so it can be ended.
 *
 * <p>The aggregate the {@code auth} context owns. Until this existed the context orchestrated over
 * {@code user} and owned nothing, and "sign out" could not mean anything: a bearer token is valid until
 * it expires, and nothing the server does can take it back. A session is the row that makes revocation
 * possible.
 *
 * <p><strong>Rotation.</strong> Every refresh mints a new secret and keeps the digest of the one it
 * replaced. A stolen refresh token is therefore usable at most once before the real client's next
 * refresh invalidates it — or, if the thief refreshes second, its use is detected. That detection is
 * the reason the previous digest is kept at all.
 *
 * <p><strong>Why a grace window.</strong> Two tabs waking together, or a request retried after a
 * connection drop, can both present the same token within a few hundred milliseconds. Treating the
 * second as theft would sign people out for having two tabs open. So a token that was rotated out
 * within {@code rotationGrace} is answered "try again" and nothing is revoked; the same token after
 * that window is theft, and the session ends.
 *
 * <p><strong>Two expiries.</strong> {@code idleExpiresAt} slides forward on each refresh, so a session
 * in use stays alive; {@code absoluteExpiresAt} never moves, so no session lives forever however busy
 * it is. Both are stored rather than computed, because the policy they came from can change and a live
 * session must keep the terms it was opened under.
 *
 * <p>Every method that needs to know the time takes it as an argument. The aggregate has no clock, so
 * its rules are testable without one and cannot drift from the caller's idea of now.
 */
@Entity
@Table(name = "auth_sessions")
@Getter
public class AuthSession {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    /** SHA-256 of the credential currently accepted. Never the credential itself. */
    @Column(nullable = false)
    private byte[] refreshTokenHash;

    /** SHA-256 of the credential this one replaced; null until the first rotation. */
    @Column
    private byte[] previousTokenHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastUsedAt;

    /** When the last rotation happened, which is what the grace window is measured from. */
    @Column
    private Instant rotatedAt;

    /** Slides forward on every refresh: a session in use does not lapse. */
    @Column(nullable = false)
    private Instant idleExpiresAt;

    /** Fixed at creation: no session outlives this, however often it is refreshed. */
    @Column(nullable = false)
    private Instant absoluteExpiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /** For JPA only. */
    protected AuthSession() {
    }

    /**
     * Opens a session for an account.
     *
     * <p>The id is supplied rather than invented here, because the credential is built around it: the
     * caller mints {@code <id>.<secret>} and hands in its digest, so the token and the row it belongs
     * to come into existence together.
     *
     * @param id          the session's identifier, already embedded in the credential
     * @param userId      the account signing in
     * @param tokenHash   the digest of the credential handed to the client
     * @param now         the instant the session starts
     * @param idle        how long the session survives without being used
     * @param absolute    how long the session may live at most, however busy
     * @return the new session, not yet persisted
     */
    public static AuthSession opened(UUID id, UUID userId, byte[] tokenHash, Instant now,
                                     Duration idle, Duration absolute) {
        AuthSession session = new AuthSession();
        session.id = id;
        session.userId = userId;
        session.refreshTokenHash = tokenHash.clone();
        session.createdAt = now;
        session.lastUsedAt = now;
        session.idleExpiresAt = now.plus(idle);
        session.absoluteExpiresAt = now.plus(absolute);
        session.revoked = false;
        return session;
    }

    /**
     * Whether this session may still be used.
     *
     * @param now the instant to judge it at
     * @return false once it is revoked, idle too long, or past its absolute cap
     */
    public boolean isActive(Instant now) {
        return !revoked && now.isBefore(idleExpiresAt) && now.isBefore(absoluteExpiresAt);
    }

    /**
     * Whether a presented credential is the one currently accepted.
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which does not stop at the first differing byte.
     * A comparison that returns early leaks, in its timing, how much of a guess was right.
     */
    public boolean matchesCurrent(byte[] presentedHash) {
        return MessageDigest.isEqual(refreshTokenHash, presentedHash);
    }

    /**
     * Whether a presented credential is the one rotated out, and recently enough to forgive.
     *
     * @param presentedHash the digest of what was presented
     * @param now           the instant it was presented
     * @param grace         how long after a rotation the old credential is still merely stale
     * @return true when this is the immediately previous credential, inside the grace window
     */
    public boolean matchesPreviousWithin(byte[] presentedHash, Instant now, Duration grace) {
        return matchesPrevious(presentedHash) && !now.isAfter(rotatedAt.plus(grace));
    }

    /**
     * Whether a presented credential is the one rotated out, whenever that was.
     *
     * <p>Outside the grace window this is the signature of a replayed credential: the legitimate client
     * has already exchanged it, so whoever is presenting it now is not that client.
     */
    public boolean matchesPrevious(byte[] presentedHash) {
        return previousTokenHash != null && rotatedAt != null
                && MessageDigest.isEqual(previousTokenHash, presentedHash);
    }

    /**
     * Exchanges the accepted credential for a new one and slides the idle expiry forward.
     *
     * <p>The idle expiry never pushes past the absolute cap: a session refreshed every minute for a
     * month still ends when the cap says it does.
     *
     * @param newTokenHash the digest of the credential now being handed to the client
     * @param now          the instant of the exchange
     * @param idle         how long the session survives without being used again
     */
    public void rotate(byte[] newTokenHash, Instant now, Duration idle) {
        this.previousTokenHash = this.refreshTokenHash;
        this.refreshTokenHash = newTokenHash.clone();
        this.rotatedAt = now;
        this.lastUsedAt = now;
        Instant slid = now.plus(idle);
        this.idleExpiresAt = slid.isAfter(absoluteExpiresAt) ? absoluteExpiresAt : slid;
    }

    /** Ends the session. Nothing it authenticated is undone; it simply stops being usable. */
    public void revoke() {
        this.revoked = true;
    }
}
