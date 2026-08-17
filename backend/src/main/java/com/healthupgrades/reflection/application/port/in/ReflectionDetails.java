package com.healthupgrades.reflection.application.port.in;

import java.time.LocalDate;

/**
 * The content of a reflection, as supplied to a use case.
 *
 * <p>A null {@code date} means "today", resolved by the service against its injected clock rather than
 * by the caller, so every entry point agrees on what today is.
 */
public record ReflectionDetails(
        LocalDate date,
        Integer difficultyRating,
        Integer benefitRating,
        String whatWorked,
        String whatDidNotWork,
        String nextAdjustment
) {}
