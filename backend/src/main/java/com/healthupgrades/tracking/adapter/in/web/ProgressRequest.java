package com.healthupgrades.tracking.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

/**
 * Request body for logging a day's progress.
 *
 * <p>Every field is optional because which one matters depends on the upgrade's tracking type. Sending
 * the field a type does not use is accepted and stored; it simply plays no part in scoring.
 *
 * @param date         the day being logged; today when null. A past date is allowed, a duplicate is not
 * @param completed    claimed completion; overridden by the server when the upgrade has a configuration
 * @param numericValue NUMERIC tracking: the value achieved; must be zero or positive
 * @param unit         NUMERIC tracking: the unit the value is in. Unstated is read as the target's unit;
 *                     a unit that disagrees with the target's means the entry is not a success
 * @param rating       RATING tracking: one to five inclusive
 * @param note         free-text note; the scored field under TEXT tracking
 */
public record ProgressRequest(
        LocalDate date,
        Boolean completed,
        @PositiveOrZero Double numericValue,
        String unit,
        @Min(1) @Max(5) Integer rating,
        String note
) {}
