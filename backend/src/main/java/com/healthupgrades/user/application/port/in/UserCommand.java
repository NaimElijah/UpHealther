package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.User; // the aggregate being persisted

/**
 * Inbound port exposing write access to users (used by registration).
 */
public interface UserCommand {

    /** Persists a new or updated user and returns the managed instance. */
    User save(User user);

    /**
     * Persists a new user and writes it immediately, so a duplicate address is discovered inside this
     * call rather than at commit.
     *
     * @param user the unsaved user
     * @return the managed instance, with its generated id
     * @throws com.healthupgrades.user.domain.model.EmailAlreadyRegisteredException if another account
     *         already holds the address, including one registered concurrently after the caller's own
     *         existence check
     */
    User register(User user);
}
