package com.healthupgrades.support;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingType;

import java.util.UUID;

/**
 * Tracking configurations for tests, one factory per measurement type.
 *
 * <p>The type decides which of an entry's value fields is scored, so a test that says
 * {@code numeric(upgradeId, 10_000, "steps")} states the whole setup its assertion depends on in one
 * line — which is the point of naming them by type rather than exposing a bare builder.
 */
public final class ATrackingConfig {

    private ATrackingConfig() {
    }

    /** A configuration for the given upgrade, defaulting to daily boolean tracking. */
    public static TrackingConfig.TrackingConfigBuilder forUpgrade(UUID upgradeId) {
        return TrackingConfig.builder()
                .id(UUID.randomUUID())
                .upgradeId(upgradeId)
                .trackingType(TrackingType.BOOLEAN)
                .frequency(Frequency.DAILY);
    }

    /** Did the user do it, yes or no. */
    public static TrackingConfig booleanTracking(UUID upgradeId) {
        return forUpgrade(upgradeId).trackingType(TrackingType.BOOLEAN).build();
    }

    /** A value to beat, stated in a unit; an entry logged in another unit is not scored (BR-8). */
    public static TrackingConfig numeric(UUID upgradeId, double target, String unit) {
        return forUpgrade(upgradeId)
                .trackingType(TrackingType.NUMERIC)
                .targetNumericValue(target)
                .targetUnit(unit)
                .build();
    }

    /** A one-to-five self-assessment. */
    public static TrackingConfig rating(UUID upgradeId) {
        return forUpgrade(upgradeId).trackingType(TrackingType.RATING).build();
    }

    /** A free-text note, where the note itself is what is scored. */
    public static TrackingConfig text(UUID upgradeId) {
        return forUpgrade(upgradeId).trackingType(TrackingType.TEXT).build();
    }
}
