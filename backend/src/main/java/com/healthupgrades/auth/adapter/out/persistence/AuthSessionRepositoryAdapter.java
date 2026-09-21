package com.healthupgrades.auth.adapter.out.persistence;

import com.healthupgrades.auth.domain.model.AuthSession; // domain aggregate
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link AuthSessionRepositoryPort} by delegating to Spring Data JPA.
 *
 * <p>The adapter exists so the Spring Data interface can stay package-private: the port is the only
 * thing the rest of the application sees, which is what keeps JPA confined to this package.
 */
@Component
@RequiredArgsConstructor
class AuthSessionRepositoryAdapter implements AuthSessionRepositoryPort {

    private final AuthSessionJpaRepository jpa; // Spring Data proxy

    /** {@inheritDoc} */
    @Override
    public AuthSession save(AuthSession session) {
        return jpa.save(session);
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
    public int revokeAllForUser(UUID userId) {
        return jpa.revokeAllForUser(userId);
    }

    /** {@inheritDoc} */
    @Override
    public int deleteUnusableAsOf(Instant now) {
        return jpa.deleteUnusableAsOf(now);
    }
}
