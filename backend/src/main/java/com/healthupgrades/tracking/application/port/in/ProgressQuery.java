package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.ProgressEntry; // returned domain aggregate

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inbound port exposing progress-entry reads as DOMAIN objects for other contexts (dashboard weekly
 * rates, the notification scheduler's daily check-in).
 */
public interface ProgressQuery {

    /** A user's progress entries on a given date. */
    List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date);

    /** A user's progress entries within an inclusive date range. */
    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end);
}
