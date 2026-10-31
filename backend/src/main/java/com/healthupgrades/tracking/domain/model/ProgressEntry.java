package com.healthupgrades.tracking.domain.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One day's progress against one upgrade.
 *
 * <p>At most one entry may exist per upgrade per date. That is a domain invariant checked before
 * insertion and backed by V1's {@code uq_progress_upgrade_date}, so a race that slips past the check
 * still fails at the database rather than producing two entries for a day. That failure is not
 * translated: it surfaces as a 500 (BR-6, #146). The
 * {@code @UniqueConstraint} below only describes that constraint: with {@code ddl-auto: validate},
 * Hibernate creates nothing.
 *
 * <p>Which of the value fields is meaningful depends on the upgrade's {@link TrackingType}: an entry
 * carries all of them and fills in the one its type calls for, so the columns are nullable by design.
 */
@Entity
@Table(name = "progress_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"upgrade_id", "date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgressEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "upgrade_id", nullable = false)
    private UUID upgradeId;

    /** Copy of the owning upgrade's owner; a composite foreign key refuses a mismatch (V13, BR-15). */
    @Column(nullable = false)
    private UUID userId;

    /** The day this entry is for, which is not necessarily the day it was logged. */
    @Column(nullable = false)
    private LocalDate date;

    /** BOOLEAN tracking: whether the user did it. Also what streaks are counted from. */
    private Boolean completed;

    /** NUMERIC tracking: the value achieved. */
    private Double numericValue;

    /** NUMERIC tracking: the unit the value is in; compared against the target's unit before scoring. */
    private String unit;

    /** RATING tracking: a one-to-five self-assessment. */
    private Integer rating;

    /** TEXT tracking: the note. Free-text on any entry type, but only here is it what is scored. */
    private String note;

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
