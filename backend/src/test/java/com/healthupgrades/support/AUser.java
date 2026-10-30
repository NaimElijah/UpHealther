package com.healthupgrades.support;

import com.healthupgrades.common.domain.port.out.AccountCredentials;
import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import java.util.UUID;

/**
 * Users for tests, built with the fields the entity requires and nothing a test has to care about.
 *
 * <p>Returns the Lombok builder rather than a finished entity so a test can override the one field it is
 * actually about — {@code AUser.aUser().email("taken@example.com").build()} — while the required fields
 * stay filled in and out of the way.
 *
 * <p>{@link #principalFor} exists because a controller's parameter is a {@link SecurityUser}, not a
 * {@link User}: it is the adapter type Spring Security exposes through {@code @AuthenticationPrincipal},
 * and a web-slice test authenticates with one. {@link #credentialsFor} is the same translation one step
 * earlier, for a test of the security adapter, which loads accounts through the kernel's port.
 */
public final class AUser {

    public static final String EMAIL = "someone@example.com";
    public static final String NAME = "Someone";

    /** A BCrypt hash. The value is irrelevant to anything that does not actually match a password. */
    public static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuvwxyz012345678901234567890123456";

    private AUser() {}

    /** A user with the required fields filled in and a fresh id. */
    public static User.UserBuilder aUser() {
        return User.builder().id(UUID.randomUUID()).name(NAME).email(EMAIL).passwordHash(PASSWORD_HASH);
    }

    /** A user with a given id, for a test that has to match a row against an owner id it already holds. */
    public static User withId(UUID id) {
        return aUser().id(id).build();
    }

    /** A user with a given id and role, for a test about what a role may reach. */
    public static User withRole(UUID id, Role role) {
        return aUser().id(id).role(role).build();
    }

    /**
     * The credentials the {@code user} context's {@code AccountCredentialsAdapter} hands the kernel for this
     * user. Repeated here rather than called, because a test of the security adapter stubs the port and
     * {@code AccountCredentialsAdapterTest} pins the adapter's own translation.
     */
    public static AccountCredentials credentialsFor(User user) {
        return new AccountCredentials(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getRole().name(),
                user.isEnabled());
    }

    /** The Spring Security principal a controller would receive for this user. */
    public static SecurityUser principalFor(User user) {
        return SecurityUser.from(credentialsFor(user));
    }

    /** The Spring Security principal for a user id, where the rest of the identity does not matter. */
    public static SecurityUser principalFor(UUID userId) {
        return principalFor(withId(userId));
    }
}
