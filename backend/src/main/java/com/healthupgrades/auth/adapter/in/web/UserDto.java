package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.user.domain.model.Role;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Public view of a user on the wire.
 *
 * <p>The account as {@code /api/auth} returns it: alone from {@code GET /me}, and inside {@link TokenPair}
 * from registration, sign-in and refresh. It lives beside the controller that returns it, so a change to
 * that wire shape is made in this context. It deliberately carries no password hash and no
 * {@code updatedAt} — everything here is safe to hand to the browser.
 *
 * @param id        the user's identifier
 * @param name      display name
 * @param email     login identity
 * @param role      what the account may do beyond owning its own records; the interface shows the
 *                  administration section only to an ADMIN, and the server refuses the paths behind
 *                  it regardless of what the interface chose to show
 * @param createdAt when the account was created
 */
public record UserDto(UUID id, String name, String email, Role role, LocalDateTime createdAt) {

    /**
     * Prints the account's id, role and creation and never its name or address. Spring MVC writes a
     * response body's {@code toString} at DEBUG, and {@code GET /me} answers every page load with this
     * record (NFR-6).
     */
    @Override
    public String toString() {
        return "UserDto[id=" + id + ", role=" + role + ", createdAt=" + createdAt + "]";
    }
}
