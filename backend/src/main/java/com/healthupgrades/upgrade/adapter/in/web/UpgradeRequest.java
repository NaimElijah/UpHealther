package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Request body for creating or replacing an upgrade.
 *
 * <p>Used by both {@code POST} and {@code PUT}. Status is absent by design — it moves only through the
 * transition endpoints — and {@code plannedStartDate} is honoured on creation only; afterwards the date
 * belongs to {@code /plan} and {@code /reschedule}.
 *
 * @param areaId           health area to file it under, optional
 * @param title            short name; required, non-blank and no longer than its column
 * @param description      longer explanation, optional
 * @param type             what kind of change this is; required
 * @param difficulty       how demanding it is, optional. On a {@code PUT}, null keeps the current
 *                         difficulty: it cannot clear one, unlike a null {@code description}, which does
 * @param plannedStartDate intended start date, optional and only meaningful on creation
 * @param targetEndDate    the date it should be done by, optional
 * @param motivation       why the user wants it, optional
 * @param successCriteria  how the user will know it worked, optional
 */
public record UpgradeRequest(
        @Nullable UUID areaId,
        @NotBlank @Size(max = TITLE_MAX) String title,
        @Nullable String description,
        @NotNull UpgradeType type,
        @Nullable Difficulty difficulty,
        @Nullable LocalDate plannedStartDate,
        @Nullable LocalDate targetEndDate,
        @Nullable String motivation,
        @Nullable String successCriteria) {
    /**
     * Mirrors {@code health_upgrades.title VARCHAR(255)}; see BR-16 for why the bound is here.
     *
     * <p>Public because {@code ColumnBoundContractTest} reads it from another package to check it
     * still agrees with the migration, which is the only thing keeping the two numbers in step.
     */
    public static final int TITLE_MAX = 255;
}
