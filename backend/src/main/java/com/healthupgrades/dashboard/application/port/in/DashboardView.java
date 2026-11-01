package com.healthupgrades.dashboard.application.port.in;

import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
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
 * @param overdue              ACTIVE upgrades past their target date
 * @param recentlyCompleted    the five most recently completed upgrades, newest completion first
 * @param weeklyCompletionRate percentage of the entries dated in the last seven days, today included, that
 *                             are marked completed; 0 when there are none
 * @param streaks              current streak per ACTIVE upgrade id
 * @param areaSummaries        one rollup per health area the user has, zero counts included; upgrades filed
 *                             under no area appear in none
 */
public record DashboardView(
        List<HealthUpgrade> active,
        List<HealthUpgrade> planned,
        List<HealthUpgrade> today,
        List<HealthUpgrade> overdue,
        List<HealthUpgrade> recentlyCompleted,
        double weeklyCompletionRate,
        Map<UUID, Integer> streaks,
        List<AreaSummary> areaSummaries) {
    /**
     * Per-area rollup of upgrade counts.
     *
     * @param areaId         the area
     * @param areaName       its display name
     * @param totalUpgrades  how many upgrades are filed under it, in any state
     * @param activeCount    how many are ACTIVE
     * @param completedCount how many are COMPLETED
     */
    public record AreaSummary(UUID areaId, String areaName, int totalUpgrades, int activeCount, int completedCount) {}
}
