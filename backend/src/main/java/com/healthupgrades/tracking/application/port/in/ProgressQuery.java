package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.ProgressEntry;
import java.util.List;
import java.util.UUID;

/**
 * Inbound port exposing a user's progress entries to other contexts, as DOMAIN objects, for the
 * dashboard's weekly rate.
 *
 * <p>Scoped by the owner. The check-in sweep's read across every user is on {@link ProgressSweepQuery}.
 */
public interface ProgressQuery {

    /**
     * The user's entries over the last seven days, today inclusive.
     *
     * <p>The one definition of "the week": the dashboard's weekly rate and {@code /api/progress/week} both
     * read it here, so the two cannot disagree about which days it spans.
     *
     * @param userId the owner
     * @return the week's entries, ordered as the store returns them
     */
    List<ProgressEntry> getWeekProgress(UUID userId);
}
