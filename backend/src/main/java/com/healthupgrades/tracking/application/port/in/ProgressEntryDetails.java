package com.healthupgrades.tracking.application.port.in;

import java.time.LocalDate;

/**
 * A progress entry as supplied to a use case.
 *
 * <p>A null {@code date} means "today", resolved by the service against its injected clock.
 * {@code completed} is advisory: when the upgrade has a tracking config, the server decides completion
 * by evaluating the entry against the target rather than trusting the caller.
 *
 * @param date         the day the entry is for; null means today
 * @param completed    claimed completion; may be null
 * @param numericValue NUMERIC tracking: the value achieved; may be null
 * @param unit         NUMERIC tracking: the unit the value is in; may be null, which is read as the target's
 * @param rating       RATING tracking: the one-to-five self-rating; may be null
 * @param note         free-text note; may be null
 */
public record ProgressEntryDetails(
        LocalDate date, Boolean completed, Double numericValue, String unit, Integer rating, String note) {}
