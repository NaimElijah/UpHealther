package com.healthupgrades.auth.domain.port.out;

import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.model.SpentCredential;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and looking up {@link AuthSession} aggregates.
 */
public interface AuthSessionRepositoryPort {

    /**
     * Stores a session that has never been stored, as one INSERT.
     *
     * <p>Apart from {@link #save} because the id is assigned rather than generated and the aggregate has
     * no version, so a general save cannot tell a new session from a stored one and reads the row before
     * writing it, on every sign-in and registration.
     *
     * <p>Must be called inside a transaction.
     *
     * @param session a session just opened, whose id no stored session holds
     * @return the same instance, now managed
     */
    AuthSession insert(AuthSession session);

    /** Persists an updated session and returns the managed instance. */
    AuthSession save(AuthSession session);

    /**
     * Persists a session and writes the change to the database before returning, rather than at commit.
     *
     * <p>For a change the caller is about to announce: a revocation for reuse is audited as done before
     * the commit, and a plain {@link #save} of a session already read issues no statement until the
     * transaction ends, so the announcement would precede the write it claims.
     *
     * <p>Must be called inside a transaction.
     */
    AuthSession saveAndFlush(AuthSession session);

    /** Looks up a session without locking it — for a read that decides nothing. */
    Optional<AuthSession> findById(UUID id);

    /**
     * Looks up a session and holds a write lock on the row until the transaction ends.
     *
     * <p>Refresh is read-decide-write on one row, and two requests arriving together would otherwise
     * both read the pre-rotation state: both would see the current credential match, both would rotate,
     * and the second would overwrite the first's — handing one client a credential the row no longer
     * accepts. Serialising the pair is what makes rotation atomic, and it is why the aggregate carries
     * no {@code @Version}: an optimistic clash would refuse the second refresh with a 409 at commit,
     * where this makes it wait for the first instead.
     *
     * <p>Must be called inside a transaction.
     */
    Optional<AuthSession> findForUpdate(UUID id);

    /**
     * Stores a rotation: the session under its new credential, and the credential the rotation retired,
     * which is remembered for as long as the session lives.
     *
     * <p>One method rather than a save and a second call, so the two writes cannot be split: a rotation
     * that stored the session and dropped its spent credential would leave a later replay of that
     * credential looking like a guess, which is S5.A1 again. Must be called inside the transaction that
     * read the session for update and rotated it, so the session and its history never disagree.
     *
     * @param session the rotated session, which must already be stored
     * @param spent   the credential {@link AuthSession#rotate} retired: its digest and when
     * @return the managed session
     */
    AuthSession saveRotation(AuthSession session, SpentCredential spent);

    /**
     * Looks up a credential a session has already spent.
     *
     * @param sessionId the session the presented credential names
     * @param tokenHash the digest of the presented credential
     * @return the spent credential and when it was spent, or empty if this session never spent it
     */
    Optional<SpentCredential> findSpent(UUID sessionId, byte[] tokenHash);

    /**
     * Ends every session an account holds.
     *
     * @param userId the account whose sessions end
     * @return how many had not been revoked, expired ones the cleanup has not yet removed included
     */
    int revokeAllForUser(UUID userId);

    /**
     * Deletes sessions that can no longer be used, so the table does not grow without bound.
     *
     * @param now the instant to judge expiry against
     * @return how many rows were removed
     */
    int deleteUnusableAsOf(Instant now);
}
