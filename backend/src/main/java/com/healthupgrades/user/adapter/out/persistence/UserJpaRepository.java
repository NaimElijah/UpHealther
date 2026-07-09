package com.healthupgrades.user.adapter.out.persistence;

import com.healthupgrades.user.domain.User; // managed entity
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository backing {@link UserRepositoryAdapter}; package-private internal detail.
 */
interface UserJpaRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email); // derived query: lookup by login email
    boolean existsByEmail(String email); // derived existence check
}
