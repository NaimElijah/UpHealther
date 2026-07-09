package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.tracking.adapter.in.web.TrackingConfigDto;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record UpgradeDto(
        UUID id,
        UUID userId,
        UUID areaId,
        String title,
        String description,
        UpgradeType type,
        UpgradeStatus status,
        Difficulty difficulty,
        LocalDate plannedStartDate,
        LocalDate actualStartDate,
        LocalDate targetEndDate,
        String motivation,
        String successCriteria,
        boolean overdue,
        Long version,
        TrackingConfigDto trackingConfig,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
