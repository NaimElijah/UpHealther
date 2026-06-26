package com.healthupgrades.dashboard.application;

import com.healthupgrades.dashboard.api.DashboardDto;
import com.healthupgrades.healtharea.domain.HealthArea;
import com.healthupgrades.healtharea.infrastructure.HealthAreaRepository;
import com.healthupgrades.tracking.domain.ProgressEntry;
import com.healthupgrades.tracking.domain.StreakCalculator;
import com.healthupgrades.tracking.infrastructure.ProgressEntryRepository;
import com.healthupgrades.upgrade.api.UpgradeDto;
import com.healthupgrades.upgrade.application.UpgradeService;
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
    private final UpgradeService upgradeService;

    public DashboardDto buildDashboard(UUID userId) {
        List<HealthUpgrade> allUpgrades = upgradeRepository.findByUserId(userId);

        List<UpgradeDto> activeUpgrades = mapByStatus(allUpgrades, UpgradeStatus.ACTIVE);
        List<UpgradeDto> plannedUpgrades = mapByStatus(allUpgrades, UpgradeStatus.PLANNED);

        LocalDate today = LocalDate.now();
        List<UpgradeDto> todayUpgrades = allUpgrades.stream()
                .filter(u -> u.isActiveOn(today))
                .map(upgradeService::toDto)
                .toList();

        List<UpgradeDto> overdueUpgrades = allUpgrades.stream()
                .filter(HealthUpgrade::isOverdue)
                .map(upgradeService::toDto)
                .toList();

        double weeklyRate = calculateWeeklyCompletionRate(userId, today);

        Map<UUID, Integer> streaks = calculateStreaks(userId, allUpgrades);

        List<UpgradeDto> recentlyCompleted = allUpgrades.stream()
                .filter(u -> u.getStatus() == UpgradeStatus.COMPLETED)
                .sorted(Comparator.comparing(HealthUpgrade::getUpdatedAt).reversed())
                .limit(5)
                .map(upgradeService::toDto)
                .toList();

        List<HealthArea> areas = areaRepository.findByUserId(userId);
        List<DashboardDto.AreaSummary> areaSummary = buildAreaSummary(areas, allUpgrades);

        return new DashboardDto(activeUpgrades, plannedUpgrades, todayUpgrades, overdueUpgrades,
                weeklyRate, streaks, recentlyCompleted, areaSummary);
    }

    private List<UpgradeDto> mapByStatus(List<HealthUpgrade> upgrades, UpgradeStatus status) {
        return upgrades.stream().filter(u -> u.getStatus() == status).map(upgradeService::toDto).toList();
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
                    int completed = (int) areaUpgrades.stream().filter(u -> u.getStatus() == UpgradeStatus.COMPLETED).count();
                    return new DashboardDto.AreaSummary(area.getId(), area.getName(), total, active, completed);
                })
                .toList();
    }
}
