package com.healthupgrades.tracking.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

public record ProgressRequest(
        LocalDate date,
        Boolean completed,
        @PositiveOrZero Double numericValue,
        String unit,
        @Min(1) @Max(5) Integer rating,
        String note
) {}
