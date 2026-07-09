package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.User; // returned domain aggregate

import java.util.Optional;

/**
 * Inbound port exposing user lookup and persistence as DOMAIN objects.
 *
 * <p>The auth context and the security adapters (JWT filter, STOMP interceptor) depend on this port
 * rather than reaching into the user persistence directly.
 */
public interface UserDirectory {

    /** Looks up a user by email. */
    Optional<User> findByEmail(String email);

    /** Whether a user already exists with the given email. */
    boolean existsByEmail(String email);

    /** Persists a new or updated user. */
    User save(User user);
}
