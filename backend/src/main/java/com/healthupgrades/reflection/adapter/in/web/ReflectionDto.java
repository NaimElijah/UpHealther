package com.healthupgrades.reflection.adapter.in.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A reflection as returned on the wire.
 *
 * @param id               the reflection's identifier
 * @param upgradeId        the upgrade it is about
 * @param userId           the author
 * @param date             the day it reflects on
 * @param difficultyRating how hard it felt, one to five, may be null
 * @param benefitRating    how worthwhile it felt, one to five, may be null
 * @param whatWorked       free text, may be null
 * @param whatDidNotWork   free text, may be null
 * @param nextAdjustment   what the user intends to change, may be null
 * @param createdAt        when it was written, which may differ from {@code date}
 */
public record ReflectionDto(
        UUID id,
        UUID upgradeId,
        UUID userId,
        LocalDate date,
        Integer difficultyRating,
        Integer benefitRating,
        String whatWorked,
        String whatDidNotWork,
        String nextAdjustment,
        LocalDateTime createdAt
) {}
