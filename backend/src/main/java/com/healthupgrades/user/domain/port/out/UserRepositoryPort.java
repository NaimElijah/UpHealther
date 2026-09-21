package com.healthupgrades.user.domain.port.out;

import com.healthupgrades.user.domain.model.Role; // the role an existence check asks about
import com.healthupgrades.user.domain.model.User; // the aggregate this port persists

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and looking up {@link User} aggregates.
 */
public interface UserRepositoryPort {

    /** Persists a new or updated user and returns the managed instance. */
    User save(User user);

    /**
     * Persists a user and flushes, so constraint violations surface inside the call.
     *
     * @throws com.healthupgrades.user.domain.model.EmailAlreadyRegisteredException if the address is
     *         already held by another account
     */
    User saveAndFlush(User user);

    /** Looks up a user by id. */
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
