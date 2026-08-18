package com.healthupgrades.tracking.application.port.in;

import java.time.LocalDate;

/**
 * A progress entry as supplied to a use case.
 *
 * <p>A null {@code date} means "today", resolved by the service against its injected clock.
 * {@code completed} is advisory: when the upgrade has a tracking config, the server decides completion
 * by evaluating the entry against the target rather than trusting the caller.
 */
public record ProgressEntryDetails(
        LocalDate date,
        Boolean completed,
        Double numericValue,
        String unit,
        Integer rating,
        String note
) {}
