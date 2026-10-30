package com.healthupgrades.tracking.application.port.in;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound port exposing computed current streaks, so the dashboard can obtain streaks without knowing
 * how they are calculated or reaching into tracking's progress store.
 */
public interface StreakQuery {

    /**
     * The current consecutive-day completion streak of each of the given upgrades of one user.
     *
     * <p>Batched only, deliberately: the dashboard asks for every running upgrade at once, and a single-id
     * variant alongside invites the per-row read this exists to avoid (NFR-14). One id is a set of one.
     *
     * <p>Scoped by the owner, as every user-facing read is: only the owner's own entries are counted, so
     * an id that is another user's upgrade measures zero rather than that user's streak (BR-15).
     *
     * @param userId     the owner of the upgrades
     * @param upgradeIds the upgrades to measure; may be empty
     * @return a streak for every id asked for, zero for one with no history of the owner's
     */
    Map<UUID, Integer> currentStreaks(UUID userId, Collection<UUID> upgradeIds);
}
