package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;

/** How an upgrade should be tracked, as supplied to a use case. */
public record TrackingConfigDetails(
        TrackingType trackingType,
        Frequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
