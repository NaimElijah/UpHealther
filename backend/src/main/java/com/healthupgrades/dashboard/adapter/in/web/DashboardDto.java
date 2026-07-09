package com.healthupgrades.dashboard.adapter.in.web;

import com.healthupgrades.upgrade.adapter.in.web.UpgradeDto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DashboardDto(
        List<UpgradeDto> activeUpgrades,
        List<UpgradeDto> plannedUpgrades,
        List<UpgradeDto> todayUpgrades,
        List<UpgradeDto> overdueUpgrades,
        double weeklyCompletionRate,
        Map<UUID, Integer> streaks,
        List<UpgradeDto> recentlyCompleted,
        List<AreaSummary> areaSummary
) {
    public record AreaSummary(UUID areaId, String areaName, int totalUpgrades, int activeCount, int completedCount) {}
}
