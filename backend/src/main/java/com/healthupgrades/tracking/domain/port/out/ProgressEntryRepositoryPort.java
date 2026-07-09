package com.healthupgrades.tracking.domain.port.out;

import com.healthupgrades.tracking.domain.ProgressEntry; // the aggregate this port persists

import java.time.LocalDate;
import java.util.List;
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

    /** A user's progress entries on a given date. */
    List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date);

    /** A user's progress entries within an inclusive date range (used for weekly rates). */
    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end);
}
