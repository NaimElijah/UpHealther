package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.ProgressEntry; // returned domain aggregate

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Inbound port exposing progress reads to other contexts: entries as DOMAIN objects for the dashboard's
 * weekly rate, and who logged on a day for the notification scheduler's daily check-in.
 */
public interface ProgressQuery {

    /**
     * Every user with at least one entry on a date, whether or not it counted — in one query, so a sweep
     * over all users asks once rather than once per user (NFR-14).
     */
    Set<UUID> findUserIdsWithEntriesOn(LocalDate date);

    /** A user's progress entries within an inclusive date range. */
    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end);
}
