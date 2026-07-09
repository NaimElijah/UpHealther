package com.healthupgrades.user.domain.port.out;

import com.healthupgrades.user.domain.User; // the aggregate this port persists

import java.util.Optional;

/**
 * Outbound port for persisting and looking up {@link User} aggregates.
 */
public interface UserRepositoryPort {

    /** Persists a new or updated user and returns the managed instance. */
    User save(User user);

    /** Looks up a user by email (the login identity). */
    Optional<User> findByEmail(String email);

    /** Whether a user already exists with the given email. */
    boolean existsByEmail(String email);
}
