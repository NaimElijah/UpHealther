package com.healthupgrades.healtharea.adapter.in.web;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Health area as returned on the wire.
 *
 * @param id          the area's identifier
 * @param userId      the owner; echoed back so a client can assert what it received
 * @param name        display name, e.g. "Nutrition"
 * @param description free-text note, may be null
 * @param priority    ordering hint chosen by the user, may be null
 * @param icon        the glyph to draw for the area, usually an emoji; may be null. Not an icon-font
 *                    key: nothing in the client turns a name such as "water_drop" into a picture, and
 *                    a value that is not drawable is replaced by a default when it is rendered
 * @param color       colour token the frontend resolves to a class, may be null
 * @param createdAt   when the area was created
 * @param updatedAt   when it was last modified
 */
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
