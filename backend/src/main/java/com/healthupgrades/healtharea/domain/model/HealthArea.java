package com.healthupgrades.healtharea.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Health area aggregate — a user-defined grouping ("Fitness", "Sleep") that upgrades are filed under.
 *
 * <p>Unlike {@code HealthUpgrade}, this aggregate keeps its setters: it has no state machine and no
 * invariant beyond "belongs to a user and has a name", so there is nothing for a factory or transition
 * method to protect.
 *
 * <p>An area is referenced by upgrades through its id only, so deleting one leaves those upgrades
 * pointing at a missing area rather than cascading.
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

    /** Stamps the creation and update timestamps before the row is first inserted. */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** Refreshes the update timestamp before each update. */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
