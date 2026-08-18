package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;

import java.util.UUID;

/**
 * An upgrade's tracking configuration as returned on the wire.
 *
 * <p>The same data is embedded in an upgrade response as {@code UpgradeTrackingConfigDto}, which
 * restates these fields in the upgrade context's own types. The duplication is deliberate — it is what
 * lets either contract change without breaking the other.
 *
 * @param id                 the configuration's identifier
 * @param upgradeId          the upgrade it configures
 * @param trackingType       how progress is measured, and therefore how success is judged
 * @param frequency          how often the upgrade is expected to be acted on, may be null
 * @param targetNumericValue NUMERIC tracking: the value to reach, else null
 * @param targetUnit         NUMERIC tracking: the unit the target is stated in, else null
 * @param requiredDaily      whether the user treats this as a must-do each day, may be null
 */
public record TrackingConfigDto(
        UUID id,
        UUID upgradeId,
        TrackingType trackingType,
        Frequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
