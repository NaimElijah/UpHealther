package com.healthupgrades.dashboard.application.port.in;

import java.util.UUID;

/**
 * Inbound port for building a user's dashboard aggregate. The web adapter drives this and maps the
 * returned {@link DashboardView} into the HTTP response.
 */
public interface DashboardQuery {

    /** Builds the dashboard aggregate for a user. */
    DashboardView getDashboard(UUID userId);
}
