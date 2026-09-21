package com.healthupgrades.user.adapter.out.persistence;

import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User; // managed entity
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository backing {@link UserRepositoryAdapter}; package-private internal detail.
 */
interface UserJpaRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email); // derived query: lookup by login email
    boolean existsByEmail(String email);

    /**
     * Whether any enabled account holds a role — asked once at startup by the administrator bootstrap.
     *
     * <p>{@code AndEnabledTrue} is load-bearing: a disabled administrator is a row that says ADMIN and
     * an installation nobody can administer.
     */
    boolean existsByRoleAndEnabledTrue(Role role); // derived existence check
}
