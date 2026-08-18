package com.healthupgrades.reflection.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user's periodic review of how one upgrade is going.
 *
 * <p>Append-only in practice: nothing in the application updates or deletes a reflection, because its
 * value is being a record of what the user thought at the time. Several may exist for the same upgrade
 * and the same date.
 *
 * <p>Every field but the identifiers and the date is optional — a reflection that is only a sentence
 * about what went wrong is a useful one, and requiring more would mean fewer get written.
 */
@Entity
@Table(name = "reflections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reflection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID upgradeId;

    @Column(nullable = false)
    private UUID userId;

    /** The day being reflected on, which need not be the day it was written. */
    @Column(nullable = false)
    private LocalDate date;

    /** How hard the user found it, one to five; optional. */
    private Integer difficultyRating;

    /** How worthwhile the user found it, one to five; optional. */
    private Integer benefitRating;

    private String whatWorked;

    private String whatDidNotWork;

    private String nextAdjustment;

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
