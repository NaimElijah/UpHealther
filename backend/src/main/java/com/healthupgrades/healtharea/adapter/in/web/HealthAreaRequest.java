package com.healthupgrades.healtharea.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating or replacing a health area.
 *
 * <p>Used by both {@code POST} and {@code PUT}: an update is a full replacement, so every optional
 * field left out is stored as null rather than kept at its previous value.
 *
 * @param name        display name; required and non-blank
 * @param description free-text note, optional
 * @param priority    ordering hint, optional
 * @param icon        icon key, optional
 * @param color       colour token, optional
 */
public record HealthAreaRequest(
        @NotBlank String name,
        String description,
        Integer priority,
        String icon,
        String color
) {}
