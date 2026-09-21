package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.Role; // the role an existence check asks about
import com.healthupgrades.user.domain.model.User; // returned domain aggregate

import java.util.List;
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

    /**
     * Reads one page of accounts, oldest first.
     *
     * <p>Takes a page number and a size rather than a {@code Pageable}: paging is a property of the
     * request, but {@code Pageable} is Spring Data's, and letting it through here would put a
     * persistence type in a contract the admin context and the security adapter both depend on. The
     * adapter turns these two numbers into one.
     *
     * <p>The order is fixed rather than a parameter, because an unordered page is not a page: without
     * it the database may return the same row on two pages and never return another.
     *
     * @param page zero-based page number
     * @param size how many accounts per page
     * @return that page of accounts, oldest first
     */
    List<User> findAll(int page, int size);

    /** How many accounts exist, for a caller paging through them. */
    long count();

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
