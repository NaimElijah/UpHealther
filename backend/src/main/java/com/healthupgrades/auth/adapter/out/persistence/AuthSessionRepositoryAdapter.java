package com.healthupgrades.auth.adapter.out.persistence;

import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.model.SpentCredential;
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Persistence adapter implementing {@link AuthSessionRepositoryPort} by delegating to Spring Data JPA.
 *
 * <p>The adapter exists so the Spring Data interface can stay package-private: the port is the only
 * thing the rest of the application sees, which is what keeps Spring Data confined to this package.
 *
 * <p>The spent credentials are written and read with native SQL rather than as an entity. They are
 * write-once rows the session's lock already serialises, keyed by a digest, and a byte array is no
 * identifier JPA can compare; an entity would add a mapping and a dirty check for two statements.
 */
@Component
@RequiredArgsConstructor
class AuthSessionRepositoryAdapter implements AuthSessionRepositoryPort {

    private final AuthSessionJpaRepository jpa;
    private final EntityManager entityManager; // the transaction's, for inserts and the spent credentials

    /** {@inheritDoc} */
    @Override
    public AuthSession insert(AuthSession session) {
        entityManager.persist(session);
        return session;
    }

    /** {@inheritDoc} */
    @Override
    public AuthSession save(AuthSession session) {
        return jpa.save(session);
    }

    /** {@inheritDoc} */
    @Override
    public AuthSession saveAndFlush(AuthSession session) {
        return jpa.saveAndFlush(session);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AuthSession> findById(UUID id) {
        return jpa.findById(id);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AuthSession> findForUpdate(UUID id) {
        return jpa.findForUpdate(id);
    }

    /** {@inheritDoc} */
    @Override
    public AuthSession saveRotation(AuthSession session, SpentCredential spent) {
        AuthSession saved = jpa.save(session);
        entityManager
                .createNativeQuery("INSERT INTO auth_session_spent_tokens (session_id, token_hash, spent_at) "
                        + "VALUES (:sessionId, :tokenHash, :spentAt)")
                .setParameter("sessionId", saved.getId())
                .setParameter("tokenHash", spent.tokenHash())
                .setParameter("spentAt", spent.spentAt())
                .executeUpdate();
        return saved;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SpentCredential> findSpent(UUID sessionId, byte[] tokenHash) {
        // Jakarta Persistence types a native query's results only as Object, whatever class it is given.
        List<?> spentAt = entityManager
                .createNativeQuery(
                        "SELECT spent_at FROM auth_session_spent_tokens "
                                + "WHERE session_id = :sessionId AND token_hash = :tokenHash",
                        Instant.class)
                .setParameter("sessionId", sessionId)
                .setParameter("tokenHash", tokenHash)
                .getResultList();
        return spentAt.stream()
                .findFirst()
                .map(Instant.class::cast)
                .map(instant -> new SpentCredential(tokenHash, instant));
    }

    /** {@inheritDoc} */
    @Override
    public int revokeAllForUser(UUID userId) {
        return jpa.revokeAllForUser(userId);
    }

    /** {@inheritDoc} */
    @Override
    public int deleteUnusableAsOf(Instant now) {
        return jpa.deleteUnusableAsOf(now);
    }
}
