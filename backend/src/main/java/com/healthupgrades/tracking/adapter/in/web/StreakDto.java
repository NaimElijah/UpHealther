package com.healthupgrades.tracking.adapter.in.web;

/**
 * An upgrade's streak figures, both counted in consecutive completed days.
 *
 * @param current days in the run ending today, or ending yesterday when today is not logged yet
 * @param longest the longest run across the upgrade's whole history
 */
public record StreakDto(int current, int longest) {}
