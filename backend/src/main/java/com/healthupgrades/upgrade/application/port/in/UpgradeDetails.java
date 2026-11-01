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
 *
 * @param areaId           the health area to file it under; may be null
 * @param title            short name; required
 * @param description      longer explanation; may be null
 * @param type             what kind of change this is; required
 * @param difficulty       how demanding it is; may be null, which on an update keeps the current difficulty
 * @param plannedStartDate the intended start date; may be null, and honoured on creation only
 * @param targetEndDate    the date it should be done by; may be null
 * @param motivation       why the user wants it; may be null
 * @param successCriteria  how the user will know it worked; may be null
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
        String successCriteria) {}
