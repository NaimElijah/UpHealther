package com.healthupgrades.tracking.application.port.in;

/**
 * An upgrade's streak figures.
 *
 * <p>A computed result rather than a stored one, so the use case returns this instead of a domain
 * aggregate — and returns it instead of a web DTO, so the application layer states its own output shape.
 *
 * @param current days completed in the run ending today, or yesterday if today is not logged yet
 * @param longest the longest such run across the upgrade's whole history
 */
public record StreakSummary(int current, int longest) {}
