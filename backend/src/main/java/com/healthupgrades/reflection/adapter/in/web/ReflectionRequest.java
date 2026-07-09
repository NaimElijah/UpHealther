package com.healthupgrades.reflection.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

public record ReflectionRequest(
        LocalDate date,
        @Min(1) @Max(5) Integer difficultyRating,
        @Min(1) @Max(5) Integer benefitRating,
        String whatWorked,
        String whatDidNotWork,
        String nextAdjustment
) {}
