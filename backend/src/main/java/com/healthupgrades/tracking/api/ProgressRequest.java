package com.healthupgrades.tracking.api;

import java.time.LocalDate;

public record ProgressRequest(
        LocalDate date,
        Boolean completed,
        Double numericValue,
        String unit,
        Integer rating,
        String note
) {}
