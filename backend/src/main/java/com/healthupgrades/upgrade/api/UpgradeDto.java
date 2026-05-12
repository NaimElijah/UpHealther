package com.healthupgrades.upgrade.api;

import com.healthupgrades.upgrade.domain.Difficulty;
import com.healthupgrades.upgrade.domain.UpgradeStatus;
import com.healthupgrades.upgrade.domain.UpgradeType;

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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
