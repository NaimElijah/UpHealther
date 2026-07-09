package com.healthupgrades.tracking.application.port.in;

import java.util.UUID;

/**
 * Inbound port exposing a computed current streak for an upgrade, so the dashboard can obtain streaks
 * without knowing how they are calculated or reaching into tracking's progress store.
 */
public interface StreakQuery {

    /** The current consecutive-day completion streak for an upgrade. */
    int currentStreak(UUID upgradeId);
}
