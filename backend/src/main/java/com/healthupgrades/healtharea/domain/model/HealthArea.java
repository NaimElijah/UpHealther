package com.healthupgrades.healtharea.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Health area aggregate — a user-defined grouping ("Fitness", "Sleep") that upgrades are filed under.
 *
 * <p>Unlike {@code HealthUpgrade}, this aggregate keeps its setters: it has no state machine for a
 * transition method to guard. Its one invariant, that it belongs to a user and has a name, is not the
 * entity's to enforce: the request's validation refuses a blank name, the service sets the owner, and the
 * NOT NULL columns refuse either missing.
 *
 * <p>An area is referenced by upgrades through its id only. Deleting one does not cascade: the foreign
 * key sets those upgrades' area to null, so they survive, unfiled (FR-8).
 */
@Entity
@Table(name = "health_areas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthArea {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    private String description;

    private Integer priority;

    private String icon;

    private String color;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Stamps the creation and update timestamps before the row is first inserted.
     *
     * <p>Reads the JVM's default zone rather than the injected clock: the one place NFR-15 allows it (ADR-020,
     * #51).
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Refreshes the update timestamp before each update, from the JVM's default zone as
     * {@link #onCreate} does.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
