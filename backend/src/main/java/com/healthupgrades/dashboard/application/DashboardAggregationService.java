package com.healthupgrades.dashboard.application;

import com.healthupgrades.dashboard.api.DashboardDto;
import com.healthupgrades.healtharea.domain.HealthArea;
import com.healthupgrades.healtharea.infrastructure.HealthAreaRepository;
import com.healthupgrades.tracking.domain.ProgressEntry;
import com.healthupgrades.tracking.domain.StreakCalculator;
import com.healthupgrades.tracking.infrastructure.ProgressEntryRepository;
import com.healthupgrades.upgrade.domain.HealthUpgrade;
import com.healthupgrades.upgrade.domain.UpgradeStatus;
import com.healthupgrades.upgrade.infrastructure.UpgradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardAggregationService {

    private final UpgradeRepository upgradeRepository;
    private final ProgressEntryRepository progressRepository;
    private final HealthAreaRepository areaRepository;
    private final StreakCalculator streakCalculator;

    public DashboardDto buildDashboard(UUID userId) {
        List<HealthUpgrade> allUpgrades = upgradeRepository.findByUserId(userId);

        int activeCount = (int) allUpgrades.stream().filter(u -> u.getStatus() == UpgradeStatus.ACTIVE).count();
        int plannedCount = (int) allUpgrades.stream().filter(u -> u.getStatus() == UpgradeStatus.PLANNED).count();

        LocalDate today = LocalDate.now();
        List<DashboardDto.UpgradeSummary> todaySummary = allUpgrades.stream()
                .filter(u -> u.isActiveOn(today))
                .map(u -> new DashboardDto.UpgradeSummary(u.getId(), u.getTitle(), u.getStatus().name()))
                .toList();

        List<DashboardDto.UpgradeSummary> overdueSummary = allUpgrades.stream()
                .filter(HealthUpgrade::isOverdue)
                .map(u -> new DashboardDto.UpgradeSummary(u.getId(), u.getTitle(), u.getStatus().name()))
                .toList();

        double weeklyRate = calculateWeeklyCompletionRate(userId, today);

        Map<UUID, Integer> streaks = calculateStreaks(userId, allUpgrades);

        List<DashboardDto.UpgradeSummary> recentCompleted = allUpgrades.stream()
                .filter(u -> u.getStatus() == UpgradeStatus.COMPLETED)
                .sorted(Comparator.comparing(HealthUpgrade::getUpdatedAt).reversed())
                .limit(5)
                .map(u -> new DashboardDto.UpgradeSummary(u.getId(), u.getTitle(), u.getStatus().name()))
                .toList();

        List<HealthArea> areas = areaRepository.findByUserId(userId);
        List<DashboardDto.AreaSummary> areaSummary = buildAreaSummary(areas, allUpgrades);

        return new DashboardDto(activeCount, plannedCount, todaySummary, overdueSummary,
                weeklyRate, streaks, recentCompleted, areaSummary);
    }

    private double calculateWeeklyCompletionRate(UUID userId, LocalDate today) {
        LocalDate weekAgo = today.minusDays(6);
        List<ProgressEntry> weekEntries = progressRepository.findByUserIdAndDateBetween(userId, weekAgo, today);
        if (weekEntries.isEmpty()) return 0.0;
        long completed = weekEntries.stream().filter(e -> Boolean.TRUE.equals(e.getCompleted())).count();
        return (double) completed / weekEntries.size() * 100.0;
    }

    private Map<UUID, Integer> calculateStreaks(UUID userId, List<HealthUpgrade> upgrades) {
        Map<UUID, Integer> streaks = new HashMap<>();
        for (HealthUpgrade upgrade : upgrades) {
            if (upgrade.getStatus() == UpgradeStatus.ACTIVE) {
                List<ProgressEntry> entries = progressRepository.findByUpgradeId(upgrade.getId());
                streaks.put(upgrade.getId(), streakCalculator.calculateCurrentStreak(entries));
            }
        }
        return streaks;
    }

    private List<DashboardDto.AreaSummary> buildAreaSummary(List<HealthArea> areas, List<HealthUpgrade> upgrades) {
        Map<UUID, List<HealthUpgrade>> byArea = upgrades.stream()
                .filter(u -> u.getAreaId() != null)
                .collect(Collectors.groupingBy(HealthUpgrade::getAreaId));

        return areas.stream()
                .map(area -> {
                    List<HealthUpgrade> areaUpgrades = byArea.getOrDefault(area.getId(), Collections.emptyList());
                    int total = areaUpgrades.size();
                    int active = (int) areaUpgrades.stream().filter(u -> u.getStatus() == UpgradeStatus.ACTIVE).count();
                    return new DashboardDto.AreaSummary(area.getId(), area.getName(), total, active);
                })
                .toList();
    }
}
