package com.healthupgrades.user.adapter.in.web;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Public view of a user on the wire.
 *
 * <p>This is the user context's published presentation model: the {@code auth} context reuses it in
 * {@code TokenPair} rather than declaring a second shape for the same thing. It deliberately carries no
 * password hash and no {@code updatedAt} — everything here is safe to hand to the browser.
 *
 * @param id        the user's identifier
 * @param name      display name
 * @param email     login identity
 * @param createdAt when the account was created
 */
public record UserDto(
        UUID id,
        String name,
        String email,
        LocalDateTime createdAt
) {}
