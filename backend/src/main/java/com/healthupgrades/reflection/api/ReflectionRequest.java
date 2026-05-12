package com.healthupgrades.reflection.api;

import java.time.LocalDate;

public record ReflectionRequest(
        LocalDate date,
        Integer difficultyRating,
        Integer benefitRating,
        String whatWorked,
        String whatDidNotWork,
        String nextAdjustment
) {}
