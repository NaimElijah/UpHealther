package com.healthupgrades.healtharea.api;

import jakarta.validation.constraints.NotBlank;

public record HealthAreaRequest(
        @NotBlank String name,
        String description,
        Integer priority,
        String icon,
        String color
) {}
