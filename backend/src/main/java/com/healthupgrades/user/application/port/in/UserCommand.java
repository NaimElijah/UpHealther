package com.healthupgrades.user.application.port.in;

import com.healthupgrades.user.domain.model.User; // the aggregate being persisted

/**
 * Inbound port exposing write access to users (used by registration).
 */
public interface UserCommand {

    /** Persists a new or updated user and returns the managed instance. */
    User save(User user);
}
