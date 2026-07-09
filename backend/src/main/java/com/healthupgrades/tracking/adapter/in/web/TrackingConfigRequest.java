package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TrackingConfigRequest(
        @NotNull TrackingType trackingType,
        Frequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
