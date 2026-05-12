package com.healthupgrades.dashboard.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DashboardDto(
        int activeUpgrades,
        int plannedUpgrades,
        List<UpgradeSummary> todayUpgrades,
        List<UpgradeSummary> overdueUpgrades,
        double weeklyCompletionRate,
        Map<UUID, Integer> streaks,
        List<UpgradeSummary> recentlyCompleted,
        List<AreaSummary> areaSummary
) {
    public record UpgradeSummary(UUID id, String title, String status) {}
    public record AreaSummary(UUID areaId, String areaName, int upgradeCount, int activeCount) {}
}
