package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for creating or replacing an upgrade.
 *
 * <p>Used by both {@code POST} and {@code PUT}. Status is absent by design — it moves only through the
 * transition endpoints — and {@code plannedStartDate} is honoured on creation only; afterwards the date
 * belongs to {@code /plan} and {@code /reschedule}.
 *
 * @param areaId           health area to file it under, optional
 * @param title            short name; required and non-blank
 * @param description      longer explanation, optional
 * @param type             what kind of change this is; required
 * @param difficulty       how demanding it is, optional
 * @param plannedStartDate intended start date, optional and only meaningful on creation
 * @param targetEndDate    the date it should be done by, optional
 * @param motivation       why the user wants it, optional
 * @param successCriteria  how the user will know it worked, optional
 */
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
