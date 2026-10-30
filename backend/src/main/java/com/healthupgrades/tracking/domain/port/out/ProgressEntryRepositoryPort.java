package com.healthupgrades.tracking.domain.port.out;

import com.healthupgrades.tracking.domain.model.ProgressEntry; // the aggregate this port persists
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port for persisting and querying {@link ProgressEntry} rows (per-day progress logs).
 * The concrete Spring Data implementation lives in the persistence adapter.
 */
public interface ProgressEntryRepositoryPort {

    /** Persists a new or updated progress entry and returns the managed instance. */
    ProgressEntry save(ProgressEntry entry);

    /** True when a progress entry already exists for the upgrade on that date (dedup guard). */
    boolean existsByUpgradeIdAndDate(UUID upgradeId, LocalDate date);

    /** All progress entries for an upgrade. */
    List<ProgressEntry> findByUpgradeId(UUID upgradeId);

    /** An upgrade's progress entries, newest first (used to compute streaks). */
    List<ProgressEntry> findByUpgradeIdOrderByDateDesc(UUID upgradeId);

    /**
     * A user's progress entries on the given upgrades, in one query and in no particular order: a streak
     * is counted over a set of dates, so the caller groups them and needs no ordering.
     *
     * <p>Scoped by the owner as well as by the ids, so an id that is another user's yields nothing
     * (BR-15) rather than that user's history.
     *
     * @param userId     the owner whose entries are read
     * @param upgradeIds the upgrades whose entries are read
     * @return the owner's entries on those upgrades
     */
    List<ProgressEntry> findByUserIdAndUpgradeIdIn(UUID userId, Collection<UUID> upgradeIds);

    /** A user's progress entries on a given date. */
    List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date);

    /** The ids of every user with at least one entry on a date. */
    Set<UUID> findUserIdsWithEntriesOn(LocalDate date);

    /** A user's progress entries within an inclusive date range (used for weekly rates). */
    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end);
}
