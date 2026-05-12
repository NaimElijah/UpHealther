package com.healthupgrades.reflection.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

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
