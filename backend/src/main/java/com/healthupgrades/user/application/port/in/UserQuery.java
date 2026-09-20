package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.User; // returned domain aggregate

import java.util.Optional;
import java.util.UUID;

/**
 * Inbound port exposing read access to users as DOMAIN objects.
 *
 * <p>The auth context and the bearer-token authenticator behind the JWT filter and the STOMP interceptor
 * look users up through this port rather than reaching into the user persistence directly.
 */
public interface UserQuery {

    /** Looks up a user by id, the identity an access token names. */
    Optional<User> findById(UUID id);

    /** Looks up a user by email (the login identity). */
    Optional<User> findByEmail(String email);

    /** Whether a user already exists with the given email. */
    boolean existsByEmail(String email);
}
