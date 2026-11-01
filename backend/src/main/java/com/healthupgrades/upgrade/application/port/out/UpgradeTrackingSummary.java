package com.healthupgrades.upgrade.application.port.out;

import com.healthupgrades.upgrade.domain.model.UpgradeTrackingFrequency;
import com.healthupgrades.upgrade.domain.model.UpgradeTrackingType;
import java.util.UUID;

/**
 * How this context wants an upgrade's tracking setup described to it.
 *
 * <p>Owned by {@code upgrade}, not by {@code tracking}: the point of {@link UpgradeTrackingSummaryPort}
 * is that this context states what it needs and another satisfies it, so this record must not be
 * expressed in the supplying context's types. The enum-valued fields are this context's mirrors of
 * tracking's enums for the same reason — importing those would recreate the dependency the port exists
 * to remove — and a contract test holds each mirror equal to its original (ADR-077).
 *
 * @param id                 the configuration's identifier
 * @param upgradeId          the upgrade it configures
 * @param trackingType       how progress is measured
 * @param frequency          how often the upgrade is expected to be acted on; may be null
 * @param targetNumericValue NUMERIC tracking: the value to reach; may be null
 * @param targetUnit         NUMERIC tracking: the unit the target is stated in; may be null
 * @param requiredDaily      whether the user treats it as a must-do each day; may be null
 */
public record UpgradeTrackingSummary(
        UUID id,
        UUID upgradeId,
        UpgradeTrackingType trackingType,
        UpgradeTrackingFrequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily) {}
