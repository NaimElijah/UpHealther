package com.healthupgrades.healtharea.application.port.in;

/**
 * The editable attributes of a health area, as supplied to a use case.
 *
 * <p>Distinct from the HTTP request record so the application layer states its own input shape rather
 * than importing the web adapter's: a change to the wire format should not change a use-case signature.
 *
 * @param name        display name; required
 * @param description free-text note; null clears it on update
 * @param priority    ordering hint; null clears it on update
 * @param icon        the glyph to draw for the area; null clears it on update
 * @param color       colour; null clears it on update
 */
public record HealthAreaDetails(String name, String description, Integer priority, String icon, String color) {}
