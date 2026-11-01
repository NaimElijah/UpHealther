package com.healthupgrades.dashboard.adapter.in.web;

import com.healthupgrades.upgrade.adapter.in.web.UpgradeDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole dashboard in one response.
 *
 * <p>Composed rather than stored: every bucket is derived at request time from the user's upgrades,
 * progress entries and health areas. The buckets overlap by design: an ACTIVE upgrade is in
 * {@code activeUpgrades} and, by its dates, may also be in {@code todayUpgrades} (inside its date window)
 * or {@code overdueUpgrades} (past its target end), never both. One activated with a start date still to
 * come is in neither. Each is serialized from a single mapped instance, so the repeated upgrades are
 * identical rather than merely equal.
 *
 * <p>All of it comes in one response, so the home page needs one request (FR-27).
 *
 * @param activeUpgrades       upgrades currently running
 * @param plannedUpgrades      upgrades committed to a date but not yet started
 * @param todayUpgrades        upgrades running today, i.e. within their date window
 * @param overdueUpgrades      running upgrades past their target end date
 * @param weeklyCompletionRate percentage of the last seven days' entries that count as successful;
 *                             zero when nothing was logged, which is indistinguishable from all-missed
 * @param streaks              current streak by upgrade id, for running upgrades only
 * @param recentlyCompleted    the five most recently finished upgrades
 * @param areaSummary          one entry per health area the user has, zero counts included; upgrades filed
 *                             under no area appear in no entry
 */
public record DashboardDto(
        List<UpgradeDto> activeUpgrades,
        List<UpgradeDto> plannedUpgrades,
        List<UpgradeDto> todayUpgrades,
        List<UpgradeDto> overdueUpgrades,
        double weeklyCompletionRate,
        Map<UUID, Integer> streaks,
        List<UpgradeDto> recentlyCompleted,
        List<AreaSummary> areaSummary) {
    /**
     * Upgrade counts for one health area.
     *
     * @param areaId         the area
     * @param areaName       its display name, denormalised so the client needs no second call
     * @param totalUpgrades  how many upgrades are filed under it, in any state
     * @param activeCount    how many are running
     * @param completedCount how many are finished
     */
    public record AreaSummary(UUID areaId, String areaName, int totalUpgrades, int activeCount, int completedCount) {}
}
