package com.healthupgrades.user.adapter.out.persistence;

import com.healthupgrades.user.domain.model.User; // domain aggregate
import com.healthupgrades.user.domain.port.out.UserRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Persistence adapter implementing {@link UserRepositoryPort} by delegating to Spring Data JPA.
 *
 * <p>The adapter exists so the Spring Data interface can stay package-private: the port is the only
 * thing the rest of the application sees, which is what keeps JPA confined to this package.
 */
@Component
@RequiredArgsConstructor
class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserJpaRepository jpa; // Spring Data proxy

    /** {@inheritDoc} */
    @Override
    public User save(User user) {
        return jpa.save(user);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<User> findByEmail(String email) {
        return jpa.findByEmail(email);
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }
}
