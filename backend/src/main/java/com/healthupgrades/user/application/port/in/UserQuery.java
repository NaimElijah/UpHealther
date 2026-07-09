package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.User; // returned domain aggregate

import java.util.Optional;

/**
 * Inbound port exposing read access to users as DOMAIN objects.
 *
 * <p>The auth context and the security adapters (JWT filter, STOMP interceptor) look users up through
 * this port rather than reaching into the user persistence directly.
 */
public interface UserQuery {

    /** Looks up a user by email (the login identity). */
    Optional<User> findByEmail(String email);

    /** Whether a user already exists with the given email. */
    boolean existsByEmail(String email);
}
