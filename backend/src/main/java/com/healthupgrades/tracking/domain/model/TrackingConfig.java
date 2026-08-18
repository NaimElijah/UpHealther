package com.healthupgrades.tracking.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * How one upgrade is tracked: the measurement type, how often it is expected, and the target to beat.
 *
 * <p>One configuration per upgrade at most, enforced by the unique constraint on {@code upgradeId}. An
 * upgrade with none simply cannot be scored — progress can still be logged against it, and
 * {@code ProgressEvaluationService} reports every such entry as unsuccessful rather than guessing.
 *
 * <p>Referenced across the context boundary by upgrade responses, but only through
 * {@code UpgradeTrackingSummary}, which restates these fields in the upgrade context's own terms.
 */
@Entity
@Table(name = "tracking_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The upgrade this configures; unique, so an upgrade has at most one configuration. */
    @Column(nullable = false, unique = true)
    private UUID upgradeId;

    /** What kind of measurement an entry carries, and therefore how success is judged. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrackingType trackingType;

    /** How often the upgrade is expected to be acted on; descriptive, not enforced. */
    @Enumerated(EnumType.STRING)
    private Frequency frequency;

    /** NUMERIC tracking: the value an entry must reach to count as a success. */
    private Double targetNumericValue;

    /** NUMERIC tracking: the unit the target is stated in; an entry in another unit is not scored. */
    private String targetUnit;

    /** Whether the user considers this a must-do every day; presentation only. */
    private Boolean requiredDaily;
}
