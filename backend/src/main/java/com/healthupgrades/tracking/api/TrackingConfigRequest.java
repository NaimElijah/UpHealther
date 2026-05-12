package com.healthupgrades.tracking.api;

import com.healthupgrades.tracking.domain.Frequency;
import com.healthupgrades.tracking.domain.TrackingType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TrackingConfigRequest(
        @NotNull TrackingType trackingType,
        Frequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
