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
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    /** How many recently completed upgrades the dashboard shows. */
    static final int RECENTLY_COMPLETED_SHOWN = 5;

    /** Scales the share of completed entries to the percentage the rate is reported in. */
    private static final double PERCENT = 100.0;

    private final UpgradeQuery upgradeQuery;
    private final ProgressQuery progressQuery;
    private final StreakQuery streakQuery;
    private final HealthAreaQuery healthAreaQuery;
    private final Clock clock; // decides the "today" every section is bucketed against

    /** {@inheritDoc} */
    @Override
    public DashboardView getDashboard(UUID userId) {
        List<HealthUpgrade> all = upgradeQuery.findByUser(userId);
        LocalDate today = LocalDate.now(clock);

        List<HealthUpgrade> active =
                all.stream().filter(u -> u.getStatus() == UpgradeStatus.ACTIVE).toList();
        List<HealthUpgrade> planned =
                all.stream().filter(u -> u.getStatus() == UpgradeStatus.PLANNED).toList();
        List<HealthUpgrade> todayList =
                all.stream().filter(u -> u.isActiveOn(today)).toList();
        List<HealthUpgrade> overdue =
                all.stream().filter(u -> u.isOverdue(today)).toList();

        double weeklyRate = calculateWeeklyCompletionRate(userId);
        Map<UUID, Integer> streaks = streakQuery.currentStreaks(
                userId, active.stream().map(HealthUpgrade::getId).toList());

        // The five most recently completed upgrades, by when each was completed: an edit moves updatedAt,
        // and FR-13 lets a completed upgrade be edited (FR-27, ADR-080). No null is ordered: V20's CHECK
        // refuses a COMPLETED row without the stamp.
        List<HealthUpgrade> recentlyCompleted = all.stream()
                .filter(u -> u.getStatus() == UpgradeStatus.COMPLETED)
                .sorted(Comparator.comparing(HealthUpgrade::getCompletedAt, Comparator.reverseOrder()))
                .limit(RECENTLY_COMPLETED_SHOWN)
                .toList();

        List<HealthArea> areas = healthAreaQuery.listByUser(userId);
        List<DashboardView.AreaSummary> areaSummaries = buildAreaSummary(areas, all);

        return new DashboardView(
                active, planned, todayList, overdue, recentlyCompleted, weeklyRate, streaks, areaSummaries);
    }

    /**
     * Percentage of the week's progress entries marked completed (0 when there are none). The week is the
     * tracking context's, the one {@code /api/progress/week} returns.
     */
    private double calculateWeeklyCompletionRate(UUID userId) {
        List<ProgressEntry> weekEntries = progressQuery.getWeekProgress(userId);
        if (weekEntries.isEmpty()) return 0.0;
        long completed = weekEntries.stream()
                .filter(e -> Boolean.TRUE.equals(e.getCompleted()))
                .count();
        return (double) completed / weekEntries.size() * PERCENT;
    }

    /** Rolls up upgrade counts per health area. */
    private List<DashboardView.AreaSummary> buildAreaSummary(List<HealthArea> areas, List<HealthUpgrade> upgrades) {
        Map<UUID, List<HealthUpgrade>> byArea = upgrades.stream()
                .filter(u -> u.getAreaId() != null)
                .collect(Collectors.groupingBy(HealthUpgrade::getAreaId));

        return areas.stream()
                .map(area -> {
                    List<HealthUpgrade> areaUpgrades = byArea.getOrDefault(area.getId(), Collections.emptyList());
                    int total = areaUpgrades.size();
                    int active = (int) areaUpgrades.stream()
                            .filter(u -> u.getStatus() == UpgradeStatus.ACTIVE)
                            .count();
                    int completed = (int) areaUpgrades.stream()
                            .filter(u -> u.getStatus() == UpgradeStatus.COMPLETED)
                            .count();
                    return new DashboardView.AreaSummary(area.getId(), area.getName(), total, active, completed);
                })
                .toList();
    }
}
