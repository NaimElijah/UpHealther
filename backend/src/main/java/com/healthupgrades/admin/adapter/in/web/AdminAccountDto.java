package com.healthupgrades.admin.adapter.in.web;

import com.healthupgrades.user.domain.model.Role;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * How an account looks to an administrator.
 *
 * <p>Its own shape rather than a reuse of {@code UserDto}, for one field: {@code enabled}. An
 * administration list that cannot show which accounts are switched off is not much of a list, and
 * putting the flag on {@code UserDto} would add an always-true field to every sign-in response for the
 * benefit of one screen.
 *
 * <p>What is <em>not</em> here is the point of the type: no password hash, and nothing about what the
 * account has been doing. An administrator sees that an account exists, what it may do, and whether it
 * is on — see ADR-016.
 *
 * @param id        the account's identifier
 * @param name      display name
 * @param email     login identity
 * @param role      what the account may do beyond owning its own records
 * @param enabled   false once an administrator has switched it off
 * @param createdAt when the account was created
 */
public record AdminAccountDto(
        UUID id,
        String name,
        String email,
        Role role,
        boolean enabled,
        LocalDateTime createdAt
) {}
