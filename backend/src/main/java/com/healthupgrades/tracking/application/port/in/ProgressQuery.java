package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.ProgressEntry; // returned domain aggregate
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inbound port exposing a user's progress entries to other contexts, as DOMAIN objects, for the
 * dashboard's weekly rate.
 *
 * <p>Scoped by the owner. The check-in sweep's read across every user is on {@link ProgressSweepQuery}.
 */
public interface ProgressQuery {

    /** A user's progress entries within an inclusive date range. */
    List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end);
}
