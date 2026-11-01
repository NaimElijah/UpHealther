package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;

/**
 * How an upgrade should be tracked, as supplied to a use case.
 *
 * @param trackingType       how progress is measured; required
 * @param frequency          how often the upgrade is expected to be acted on; may be null
 * @param targetNumericValue NUMERIC tracking: the value an entry must reach; may be null
 * @param targetUnit         NUMERIC tracking: the unit the target is in; may be null
 * @param requiredDaily      whether this is a must-do each day; may be null
 */
public record TrackingConfigDetails(
        TrackingType trackingType,
        Frequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily) {}
