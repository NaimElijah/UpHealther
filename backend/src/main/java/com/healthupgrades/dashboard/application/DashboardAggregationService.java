package com.healthupgrades.dashboard.application;

import com.healthupgrades.dashboard.application.port.in.DashboardQuery;
import com.healthupgrades.dashboard.application.port.in.DashboardView;
import com.healthupgrades.healtharea.application.port.in.HealthAreaQuery;
import com.healthupgrades.healtharea.domain.model.HealthArea;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
import com.healthupgrades.tracking.application.port.in.StreakQuery;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service that aggregates a user's dashboard from other bounded contexts.
 *
 * <p>Reads exclusively through inbound query ports ({@link UpgradeQuery}, {@link ProgressQuery},
 * {@link StreakQuery}, {@link HealthAreaQuery}) and returns a domain-level {@link DashboardView}. All
 * DTO/HTTP shaping is left to the web adapter, so this service depends on no other context's web layer.
 */
@Service
@RequiredArgsConstructor
public class DashboardAggregationService implements DashboardQuery {

    private final UpgradeQuery upgradeQuery; // inbound port: upgrades
    private final ProgressQuery progressQuery; // inbound port: progress entries
    private final StreakQuery streakQuery; // inbound port: computed streaks
    private final HealthAreaQuery healthAreaQuery; // inbound port: health areas
    private final Clock clock; // decides the "today" every section is bucketed against

    /** {@inheritDoc} */
    @Override
    public DashboardView getDashboard(UUID userId) {
        List<HealthUpgrade> all = upgradeQuery.findByUser(userId); // the user's whole portfolio
        LocalDate today = LocalDate.now(clock);

        // Bucket the upgrades by the dashboard's sections (kept as domain objects).
        List<HealthUpgrade> active = all.stream().filter(u -> u.getStatus() == UpgradeStatus.ACTIVE).toList();
        List<HealthUpgrade> planned = all.stream().filter(u -> u.getStatus() == UpgradeStatus.PLANNED).toList();
        List<HealthUpgrade> todayList = all.stream().filter(u -> u.isActiveOn(today)).toList();
        List<HealthUpgrade> overdue = all.stream().filter(u -> u.isOverdue(today)).toList();

        double weeklyRate = calculateWeeklyCompletionRate(userId, today);
        Map<UUID, Integer> streaks = calculateStreaks(all);

        // The five most recently completed upgrades.
        List<HealthUpgrade> recentlyCompleted = all.stream()
                .filter(u -> u.getStatus() == UpgradeStatus.COMPLETED)
                .sorted(Comparator.comparing(HealthUpgrade::getUpdatedAt).reversed())
                .limit(5)
                .toList();

        List<HealthArea> areas = healthAreaQuery.listByUser(userId);
        List<DashboardView.AreaSummary> areaSummaries = buildAreaSummary(areas, all);

        return new DashboardView(active, planned, todayList, overdue, recentlyCompleted,
                weeklyRate, streaks, areaSummaries);
    }

    /** Percentage of the last 7 days' progress entries marked completed (0 when there are none). */
    private double calculateWeeklyCompletionRate(UUID userId, LocalDate today) {
        LocalDate weekAgo = today.minusDays(6);
        List<ProgressEntry> weekEntries = progressQuery.findByUserIdAndDateBetween(userId, weekAgo, today);
        if (weekEntries.isEmpty()) return 0.0;
        long completed = weekEntries.stream().filter(e -> Boolean.TRUE.equals(e.getCompleted())).count();
        return (double) completed / weekEntries.size() * 100.0;
    }

    /** Current streak per ACTIVE upgrade, obtained from the tracking context. */
    private Map<UUID, Integer> calculateStreaks(List<HealthUpgrade> upgrades) {
        Map<UUID, Integer> streaks = new HashMap<>();
        for (HealthUpgrade upgrade : upgrades) {
            if (upgrade.getStatus() == UpgradeStatus.ACTIVE) {
                streaks.put(upgrade.getId(), streakQuery.currentStreak(upgrade.getId()));
            }
        }
        return streaks;
    }

    /** Rolls up upgrade counts per health area. */
    private List<DashboardView.AreaSummary> buildAreaSummary(List<HealthArea> areas, List<HealthUpgrade> upgrades) {
        // Group upgrades by their area so each area's counts are a single pass.
        Map<UUID, List<HealthUpgrade>> byArea = upgrades.stream()
                .filter(u -> u.getAreaId() != null)
                .collect(Collectors.groupingBy(HealthUpgrade::getAreaId));

        return areas.stream()
                .map(area -> {
                    List<HealthUpgrade> areaUpgrades = byArea.getOrDefault(area.getId(), Collections.emptyList());
                    int total = areaUpgrades.size();
                    int active = (int) areaUpgrades.stream().filter(u -> u.getStatus() == UpgradeStatus.ACTIVE).count();
                    int completed = (int) areaUpgrades.stream().filter(u -> u.getStatus() == UpgradeStatus.COMPLETED).count();
                    return new DashboardView.AreaSummary(area.getId(), area.getName(), total, active, completed);
                })
                .toList();
    }
}
