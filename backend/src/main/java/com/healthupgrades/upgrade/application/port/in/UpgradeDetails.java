package com.healthupgrades.upgrade.application.port.in;

import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The attributes of an upgrade a caller may set, as supplied to a use case.
 *
 * <p>{@code plannedStartDate} is honoured on creation only; afterwards the date moves through the
 * reschedule use case, which is the transition that carries the lifecycle rules.
 */
public record UpgradeDetails(
        UUID areaId,
        String title,
        String description,
        UpgradeType type,
        Difficulty difficulty,
        LocalDate plannedStartDate,
        LocalDate targetEndDate,
        String motivation,
        String successCriteria
) {}
