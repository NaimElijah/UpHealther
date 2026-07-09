package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;

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
