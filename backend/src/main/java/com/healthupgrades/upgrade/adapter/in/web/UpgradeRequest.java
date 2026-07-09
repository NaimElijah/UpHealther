package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
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
