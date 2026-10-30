package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.Role; // the role an existence check asks about
import com.healthupgrades.user.domain.model.User; // returned domain aggregate
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound port exposing read access to users as DOMAIN objects.
 *
 * <p>The auth and admin contexts look users up through this port rather than reaching into the user
 * persistence directly, and so does {@code AccountCredentialsAdapter}, which supplies the accounts the
 * security adapter behind the JWT filter and the STOMP interceptor authenticates (ADR-062).
 */
public interface UserQuery {

    /** Looks up a user by id, the identity an access token names. */
    Optional<User> findById(UUID id);

    /** Looks up a user by email (the login identity). */
    Optional<User> findByEmail(String email);

    /** Whether a user already exists with the given email. */
    boolean existsByEmail(String email);

    /**
     * Whether any <em>enabled</em> account holds a given role.
     *
     * <p>Exists for one caller — the administrator bootstrap, which must do nothing once the
     * installation can already be administered. Enabled is part of the question, not a detail: two
     * administrators can disable each other, and the rows then still say ADMIN while nobody can
     * actually administer anything.
     */
    boolean existsEnabledWithRole(Role role);
}
