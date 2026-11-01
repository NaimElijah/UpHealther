package com.healthupgrades.reflection.application.port.in;

import java.time.LocalDate;

/**
 * The content of a reflection, as supplied to a use case.
 *
 * <p>A null {@code date} means "today", resolved by the service against its injected clock rather than
 * by the caller, so every entry point agrees on what today is.
 *
 * @param date             the day being reflected on; null means today
 * @param difficultyRating how hard it felt, one to five; may be null. Not range-checked here: the web request
 *                         bounds it, and the database refuses a value outside one to five (BR-19)
 * @param benefitRating    how worthwhile it felt, one to five; may be null, and bounded as above
 * @param whatWorked       free text; may be null
 * @param whatDidNotWork   free text; may be null
 * @param nextAdjustment   what the user intends to change; may be null
 */
public record ReflectionDetails(
        LocalDate date,
        Integer difficultyRating,
        Integer benefitRating,
        String whatWorked,
        String whatDidNotWork,
        String nextAdjustment) {}
