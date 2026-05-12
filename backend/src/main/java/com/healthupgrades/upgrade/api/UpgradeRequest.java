package com.healthupgrades.upgrade.api;

import com.healthupgrades.upgrade.domain.Difficulty;
import com.healthupgrades.upgrade.domain.UpgradeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record UpgradeRequest(
        UUID areaId,
        @NotBlank String title,
        String description,
        @NotNull UpgradeType type,
        Difficulty difficulty,
        LocalDate plannedStartDate,
        LocalDate targetEndDate,
        String motivation,
        String successCriteria
) {}
