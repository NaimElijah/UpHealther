package com.healthupgrades.tracking.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProgressDto(
        UUID id,
        UUID upgradeId,
        UUID userId,
        LocalDate date,
        Boolean completed,
        Double numericValue,
        String unit,
        Integer rating,
        String note,
        LocalDateTime createdAt
) {}
