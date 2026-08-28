package com.healthupgrades.healtharea.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating or replacing a health area.
 *
 * <p>Used by both {@code POST} and {@code PUT}: an update is a full replacement, so every optional
 * field left out is stored as null rather than kept at its previous value.
 *
 * @param name        display name; required, non-blank and no longer than its column
 * @param description free-text note, optional
 * @param priority    ordering hint, optional
 * @param icon        the glyph to draw for the area, usually an emoji; optional. Not an icon-font key
 *                    — see {@link HealthAreaDto}
 * @param color       colour token, optional
 */
public record HealthAreaRequest(
        @NotBlank @Size(max = NAME_MAX) String name,
        String description,
        Integer priority,
        @Size(max = ICON_MAX) String icon,
        @Size(max = COLOR_MAX) String color
) {
    /** Mirrors {@code health_areas.name VARCHAR(255)}; see BR-16 for why the bound is here. */
    public static final int NAME_MAX = 255;

    /** Mirrors {@code health_areas.icon VARCHAR(100)}. */
    public static final int ICON_MAX = 100;

    /** Mirrors {@code health_areas.color VARCHAR(50)}, the narrowest of the three. */
    public static final int COLOR_MAX = 50;
}
