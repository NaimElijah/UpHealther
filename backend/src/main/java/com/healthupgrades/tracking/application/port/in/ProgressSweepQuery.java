package com.healthupgrades.tracking.application.port.in;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Inbound port for the scheduled sweeps that read progress across every user: the daily check-in nudge.
 *
 * <p><strong>Every user's rows, not one user's.</strong> Nothing here is scoped by an owner, so it exists
 * only for scheduled work, and only the jobs' application services may depend on it, which
 * {@code HexagonalArchitectureTest} enforces. A request-driven read takes the owner's id and lives on
 * the context's user-facing query port instead (BR-15).
 */
public interface ProgressSweepQuery {

    /**
     * Every user with at least one entry on a date, whether or not it counted — in one query, so a sweep
     * over all users asks once rather than once per user (NFR-14).
     *
     * @param date the day to look at
     * @return the ids of every user who logged anything on it
     */
    Set<UUID> findUserIdsWithEntriesOn(LocalDate date);
}
