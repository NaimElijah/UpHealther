package com.healthupgrades.healtharea.application.port.in;

import com.healthupgrades.healtharea.domain.model.HealthArea; // returned domain aggregate

import java.util.List;
import java.util.UUID;

/**
 * Inbound port exposing a user's health areas to other contexts: as DOMAIN objects for the dashboard's
 * area summaries, and as an ownership answer for the upgrade context, neither of which touches the
 * health-area persistence.
 */
public interface HealthAreaQuery {

    /** All health areas owned by a user. */
    List<HealthArea> listByUser(UUID userId);

    /**
     * Whether an area exists and belongs to the user.
     *
     * <p>Answers {@code false} for an area that belongs to somebody else and for one that does not exist,
     * without saying which — the same indistinguishability BR-15 gives a direct read.
     *
     * @param userId the would-be owner
     * @param areaId the area's identifier
     * @return true only when the area exists and is the user's
     */
    boolean ownsArea(UUID userId, UUID areaId);
}
