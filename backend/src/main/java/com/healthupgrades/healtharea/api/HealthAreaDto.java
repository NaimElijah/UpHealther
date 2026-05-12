package com.healthupgrades.healtharea.api;

import java.time.LocalDateTime;
import java.util.UUID;

public record HealthAreaDto(
        UUID id,
        UUID userId,
        String name,
        String description,
        Integer priority,
        String icon,
        String color,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
