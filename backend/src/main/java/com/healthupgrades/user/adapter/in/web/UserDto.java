package com.healthupgrades.user.adapter.in.web;

import com.healthupgrades.user.domain.model.Role;

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
 * @param role      what the account may do beyond owning its own records; the interface shows the
 *                  administration section only to an ADMIN, and the server refuses the paths behind
 *                  it regardless of what the interface chose to show
 * @param createdAt when the account was created
 */
public record UserDto(
        UUID id,
        String name,
        String email,
        Role role,
        LocalDateTime createdAt
) {}
