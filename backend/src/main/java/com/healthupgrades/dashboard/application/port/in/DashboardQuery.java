package com.healthupgrades.dashboard.application.port.in;

import java.util.UUID;

/**
 * Inbound port for building a user's dashboard aggregate. The web adapter drives this and maps the
 * returned {@link DashboardView} into the HTTP response.
 */
public interface DashboardQuery {

    /**
     * Builds the dashboard aggregate for a user.
     *
     * @param userId the owner whose data is summarised
     * @return the assembled view; empty buckets rather than null for a user with no upgrades
     */
    DashboardView getDashboard(UUID userId);
}
