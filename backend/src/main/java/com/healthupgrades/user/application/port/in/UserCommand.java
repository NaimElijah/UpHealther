package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.User; // the aggregate being persisted

/**
 * Inbound port exposing write access to users: registration writes through it, and so do the
 * administrator's changes to an account ({@code AdminUserService}) and the administrator bootstrap
 * ({@code AdminBootstrapService}).
 */
public interface UserCommand {

    /**
     * Persists a new or updated user, writing it immediately, so a write that another one overtook is
     * refused inside this call rather than at commit.
     *
     * @param user the user to persist
     * @return the managed instance
     * @throws com.healthupgrades.user.domain.model.AccountModifiedConcurrentlyException if another write
     *         to the account committed after this copy of it was loaded (ADR-073)
     */
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
