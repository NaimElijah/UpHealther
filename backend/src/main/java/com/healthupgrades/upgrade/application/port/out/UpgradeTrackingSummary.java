package com.healthupgrades.upgrade.application.port.out;

import java.util.UUID;

/**
 * How this context wants an upgrade's tracking setup described to it.
 *
 * <p>Owned by {@code upgrade}, not by {@code tracking}: the point of {@link UpgradeTrackingSummaryPort}
 * is that this context states what it needs and another satisfies it, so this record must not be
 * expressed in the supplying context's types. The enum-valued fields are carried as their names for the
 * same reason — importing tracking's enums here would recreate the dependency the port exists to remove.
 */
public record UpgradeTrackingSummary(
        UUID id,
        UUID upgradeId,
        String trackingType,
        String frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
