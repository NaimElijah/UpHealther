package com.healthupgrades.reflection.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

/**
 * Request body for writing a reflection.
 *
 * <p>Everything is optional, including the ratings: a reflection with only a note is a legitimate one,
 * and demanding a score to record a thought would just stop people writing them.
 *
 * @param date             the day being reflected on; today when null
 * @param difficultyRating how hard it felt, one to five inclusive
 * @param benefitRating    how worthwhile it felt, one to five inclusive
 * @param whatWorked       free text
 * @param whatDidNotWork   free text
 * @param nextAdjustment   what the user intends to change
 */
public record ReflectionRequest(
        LocalDate date,
        @Min(1) @Max(5) Integer difficultyRating,
        @Min(1) @Max(5) Integer benefitRating,
        String whatWorked,
        String whatDidNotWork,
        String nextAdjustment
) {}
