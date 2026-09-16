package com.healthupgrades.user.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User aggregate — a plain JPA entity.
 *
 * <p>Authentication and authorization concerns (Spring Security's {@code UserDetails}) live in the
 * security adapter ({@code SecurityUser}), not on this domain model.
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
}
