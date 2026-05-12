package com.healthupgrades.tracking.api;

import com.healthupgrades.tracking.domain.Frequency;
import com.healthupgrades.tracking.domain.TrackingType;

import java.util.UUID;

public record TrackingConfigDto(
        UUID id,
        UUID upgradeId,
        TrackingType trackingType,
        Frequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
