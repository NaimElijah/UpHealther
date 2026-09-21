package com.healthupgrades.auth.domain.port.out;

import com.healthupgrades.auth.domain.model.AuthSession; // the aggregate this port persists

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and looking up {@link AuthSession} aggregates.
 */
public interface AuthSessionRepositoryPort {

    /** Persists a new or updated session and returns the managed instance. */
    AuthSession save(AuthSession session);

    /** Looks up a session without locking it — for a read that decides nothing. */
    Optional<AuthSession> findById(UUID id);

    /**
     * Looks up a session and holds a write lock on the row until the transaction ends.
     *
     * <p>Refresh is read-decide-write on one row, and two requests arriving together would otherwise
     * both read the pre-rotation state: both would see the current credential match, both would rotate,
     * and the second would overwrite the first's — handing one client a credential the row no longer
     * accepts. Serialising the pair is what makes rotation atomic, and it is why the aggregate carries
     * no {@code @Version}: an optimistic clash would surface as an unmapped 500 at commit, outside any
     * handler, where this waits instead.
     *
     * <p>Must be called inside a transaction.
     */
    Optional<AuthSession> findForUpdate(UUID id);

    /**
     * Ends every session an account holds.
     *
     * @return how many were still active
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
