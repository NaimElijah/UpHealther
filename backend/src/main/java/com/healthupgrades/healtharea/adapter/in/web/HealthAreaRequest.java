package com.healthupgrades.healtharea.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

public record HealthAreaRequest(
        @NotBlank String name,
        String description,
        Integer priority,
        String icon,
        String color
) {}
