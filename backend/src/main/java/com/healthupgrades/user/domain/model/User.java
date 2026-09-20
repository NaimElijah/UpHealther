package com.healthupgrades.user.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * User aggregate — a plain JPA entity.
 *
 * <p>Authentication and authorization concerns (Spring Security's {@code UserDetails}) live in the
 * security adapter ({@code SecurityUser}), not on this domain model. What lives here is the state
 * those concerns read: the account's {@link Role} and whether it is enabled. Neither has a setter —
 * they change only through {@link #changeRole}, {@link #disable} and {@link #enable}, so the places
 * an account gains a privilege or loses its access are countable.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    /**
     * What this account may do beyond owning its own records. Defaults to {@link Role#USER}: a
     * registration names no role, and an account that arrived without one must be ordinary rather
     * than unauthorised.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Role role = Role.USER;

    /**
     * Whether the account may authenticate at all. Disabling is reversible and destroys nothing —
     * the account's records stay exactly as they were.
     */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Stamps the creation and update timestamps before the row is first inserted, and normalises the
     * email as a backstop for a caller that forgot to: the database refuses an unnormalised address, so
     * the alternative is a constraint violation at flush.
     */
    @PrePersist
    protected void onCreate() {
        email = EmailAddress.normalise(email);
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** Refreshes the update timestamp before each update, normalising the email for the same reason. */
    @PreUpdate
    protected void onUpdate() {
        email = EmailAddress.normalise(email);
        updatedAt = LocalDateTime.now();
    }

    /**
     * Grants or revokes a role.
     *
     * @param newRole the role the account is to hold
     * @throws NullPointerException if {@code newRole} is null; an account without a role is a state
     *                              the column refuses and the application could not read back
     */
    public void changeRole(Role newRole) {
        this.role = Objects.requireNonNull(newRole, "a role is required");
    }

    /**
     * Turns the account off. It can no longer authenticate, and nothing it owns is touched.
     *
     * <p>Revoking the sessions already issued to it is the caller's job: this aggregate knows nothing
     * about sessions, and a disabled account is refused on its next request either way.
     */
    public void disable() {
        this.enabled = false;
    }

    /** Turns the account back on, with everything it owned still in place. */
    public void enable() {
        this.enabled = true;
    }
}
