package com.healthupgrades.dashboard.application.port.in;

import com.healthupgrades.upgrade.domain.model.HealthUpgrade; // domain aggregate carried in each bucket

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application-level result of building a user's dashboard, expressed with DOMAIN objects and computed
 * metrics — deliberately free of any web DTO. The dashboard web adapter maps this into the HTTP payload.
 *
 * @param active               upgrades currently ACTIVE
 * @param planned              upgrades currently PLANNED
 * @param today                upgrades active on today's date
 * @param overdue              upgrades past their target date
 * @param recentlyCompleted    the five most recently completed upgrades
 * @param weeklyCompletionRate percentage of the last week's progress entries marked completed
 * @param streaks              current streak per ACTIVE upgrade id
 * @param areaSummaries        per-health-area rollups
 */
public record DashboardView(
        List<HealthUpgrade> active,
        List<HealthUpgrade> planned,
        List<HealthUpgrade> today,
        List<HealthUpgrade> overdue,
        List<HealthUpgrade> recentlyCompleted,
        double weeklyCompletionRate,
        Map<UUID, Integer> streaks,
        List<AreaSummary> areaSummaries
) {
    /** Per-area rollup of upgrade counts. */
    public record AreaSummary(UUID areaId, String areaName, int totalUpgrades, int activeCount, int completedCount) {}
}
