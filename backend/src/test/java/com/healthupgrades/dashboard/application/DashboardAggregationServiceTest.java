package com.healthupgrades.dashboard.application;

import com.healthupgrades.dashboard.application.port.in.DashboardView;
import com.healthupgrades.healtharea.application.port.in.HealthAreaQuery;
import com.healthupgrades.healtharea.domain.model.HealthArea;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
import com.healthupgrades.tracking.application.port.in.StreakQuery;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers FR-27 — everything the dashboard shows in one request: which upgrade lands in which bucket, the
 * weekly completion rate, the per-upgrade streaks and the per-area counts.
 *
 * <p>Every collaborator is a mocked inbound port, so what is under test is the composition itself — the
 * bucketing and the arithmetic — rather than any other context's behaviour.
 *
 * <p>This is the context that shipped issue #20, where the rate reached the user a hundred times too
 * large. The unit the rate is stated in is therefore pinned explicitly below rather than left to be
 * inferred, because both ends of that bug looked reasonable in isolation.
 */
@ExtendWith(MockitoExtension.class)
class DashboardAggregationServiceTest {

    @Mock UpgradeQuery upgradeQuery;
    @Mock ProgressQuery progressQuery;
    @Mock StreakQuery streakQuery;
    @Mock HealthAreaQuery healthAreaQuery;

    /** Fixed so the dashboard's "today" buckets are decided here, not by when the suite runs. */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC);
    private final LocalDate today = LocalDate.of(2026, 3, 15);

    private DashboardAggregationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DashboardAggregationService(upgradeQuery, progressQuery, streakQuery,
                healthAreaQuery, fixedClock);
    }

    // A minimal upgrade with just the fields the aggregation reads.
    private HealthUpgrade upgrade(UpgradeStatus status) {
        return HealthUpgrade.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .status(status)
                .updatedAt(today.atStartOfDay())
                .build();
    }

    // A minimal weekly progress entry, completed or not.
    private ProgressEntry entry(boolean completed) {
        return ProgressEntry.builder()
                .id(UUID.randomUUID())
                .upgradeId(UUID.randomUUID())
                .userId(userId)
                .date(today)
                .completed(completed)
                .build();
    }

    @Test
    void GivenUpgradesAcrossEveryStatus_WhenTheDashboardIsBuilt_ThenTheCountsAndTheWeeklyRateAreCorrect() {
        List<HealthUpgrade> upgrades = List.of(
                upgrade(UpgradeStatus.ACTIVE),
                upgrade(UpgradeStatus.ACTIVE),
                upgrade(UpgradeStatus.PLANNED),
                upgrade(UpgradeStatus.COMPLETED));
        when(upgradeQuery.findByUser(userId)).thenReturn(upgrades);
        // 2 of 4 weekly entries completed -> 50%
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(entry(true), entry(true), entry(false), entry(false)));
        when(streakQuery.currentStreak(any())).thenReturn(4);
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of());

        DashboardView view = service.getDashboard(userId);

        assertThat(view.active()).hasSize(2);
        assertThat(view.planned()).hasSize(1);
        assertThat(view.recentlyCompleted()).hasSize(1);
        assertThat(view.weeklyCompletionRate()).isEqualTo(50.0);
        // streaks are computed only for ACTIVE upgrades
        assertThat(view.streaks()).hasSize(2);
    }

    // ---- The weekly completion rate ----

    @Test
    void GivenHalfTheWeeksEntriesCount_WhenTheRateIsComputed_ThenItIsAPercentageAndNotAFraction() {
        // Issue #20: the rate reached the user a hundred times too large. The fix settled that this
        // number is already a percentage, so anything downstream must render it as-is. Asserting 50.0
        // rather than 0.5 is what stops it being "corrected" back into a fraction.
        when(upgradeQuery.findByUser(userId)).thenReturn(List.of());
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(entry(true), entry(false)));
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of());

        assertThat(service.getDashboard(userId).weeklyCompletionRate()).isEqualTo(50.0);
    }

    @Test
    void GivenNothingLoggedThisWeek_WhenTheRateIsComputed_ThenItIsZeroRatherThanUndefined() {
        // Dividing by an empty week would be NaN, which serialises to invalid JSON.
        when(upgradeQuery.findByUser(userId)).thenReturn(List.of());
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of());

        assertThat(service.getDashboard(userId).weeklyCompletionRate()).isEqualTo(0.0);
    }

    @Test
    void GivenAnEntryWithNoCompletionVerdict_WhenTheRateIsComputed_ThenItCountsAsNotCompleted() {
        // An unscored entry — one on an upgrade with no tracking config — is not a success.
        ProgressEntry unscored = ProgressEntry.builder()
                .id(UUID.randomUUID()).upgradeId(UUID.randomUUID()).userId(userId).date(today).build();
        when(upgradeQuery.findByUser(userId)).thenReturn(List.of());
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(entry(true), unscored));
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of());

        assertThat(service.getDashboard(userId).weeklyCompletionRate()).isEqualTo(50.0);
    }

    @Test
    void GivenTheDashboardIsBuilt_WhenTheWeekIsSelected_ThenItSpansSevenDaysEndingToday() {
        when(upgradeQuery.findByUser(userId)).thenReturn(List.of());
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of());

        service.getDashboard(userId);

        verify(progressQuery).findByUserIdAndDateBetween(userId, today.minusDays(6), today);
    }

    // ---- Buckets ----

    @Test
    void GivenAnActiveUpgradePastItsTargetDate_WhenTheDashboardIsBuilt_ThenItIsListedAsOverdue() {
        HealthUpgrade overdue = HealthUpgrade.builder()
                .id(UUID.randomUUID()).userId(userId).status(UpgradeStatus.ACTIVE)
                .targetEndDate(today.minusDays(1)).updatedAt(today.atStartOfDay()).build();
        stubEmptyExceptUpgrades(List.of(overdue));
        when(streakQuery.currentStreak(any())).thenReturn(0);

        assertThat(service.getDashboard(userId).overdue()).containsExactly(overdue);
    }

    @Test
    void GivenAnActiveUpgradeRunningToday_WhenTheDashboardIsBuilt_ThenItIsListedForToday() {
        HealthUpgrade running = HealthUpgrade.builder()
                .id(UUID.randomUUID()).userId(userId).status(UpgradeStatus.ACTIVE)
                .actualStartDate(today.minusDays(3)).targetEndDate(today.plusDays(3))
                .updatedAt(today.atStartOfDay()).build();
        stubEmptyExceptUpgrades(List.of(running));
        when(streakQuery.currentStreak(any())).thenReturn(0);

        assertThat(service.getDashboard(userId).today()).containsExactly(running);
    }

    @Test
    void GivenMoreThanFiveCompletedUpgrades_WhenTheDashboardIsBuilt_ThenOnlyTheFiveMostRecentAreShown() {
        List<HealthUpgrade> completed = List.of(
                completedAt(today.minusDays(1)), completedAt(today.minusDays(2)),
                completedAt(today.minusDays(3)), completedAt(today.minusDays(4)),
                completedAt(today.minusDays(5)), completedAt(today.minusDays(6)),
                completedAt(today.minusDays(7)));
        stubEmptyExceptUpgrades(completed);

        List<HealthUpgrade> recent = service.getDashboard(userId).recentlyCompleted();

        assertThat(recent).hasSize(5);
        assertThat(recent).containsExactly(completed.get(0), completed.get(1), completed.get(2),
                completed.get(3), completed.get(4));
    }

    @Test
    void GivenAnAccountWithNothingInIt_WhenTheDashboardIsBuilt_ThenEverySectionIsEmptyRatherThanNull() {
        // The first thing a new user sees. Every list has to render.
        stubEmptyExceptUpgrades(List.of());

        DashboardView view = service.getDashboard(userId);

        assertThat(view.active()).isEmpty();
        assertThat(view.planned()).isEmpty();
        assertThat(view.today()).isEmpty();
        assertThat(view.overdue()).isEmpty();
        assertThat(view.recentlyCompleted()).isEmpty();
        assertThat(view.streaks()).isEmpty();
        assertThat(view.areaSummaries()).isEmpty();
        assertThat(view.weeklyCompletionRate()).isEqualTo(0.0);
    }

    // ---- Streaks ----

    @Test
    void GivenUpgradesThatAreNotActive_WhenTheDashboardIsBuilt_ThenNoStreakIsAskedForThem() {
        // A streak is a per-upgrade query into the tracking context. Asking for one per upgrade rather
        // than per active upgrade is a cost paid on every dashboard load.
        stubEmptyExceptUpgrades(List.of(upgrade(UpgradeStatus.PLANNED), upgrade(UpgradeStatus.PAUSED),
                upgrade(UpgradeStatus.COMPLETED), upgrade(UpgradeStatus.ABANDONED)));

        assertThat(service.getDashboard(userId).streaks()).isEmpty();
        verify(streakQuery, never()).currentStreak(any());
    }

    @Test
    void GivenAnActiveUpgrade_WhenTheDashboardIsBuilt_ThenItsStreakIsKeyedByItsId() {
        HealthUpgrade active = upgrade(UpgradeStatus.ACTIVE);
        stubEmptyExceptUpgrades(List.of(active));
        when(streakQuery.currentStreak(active.getId())).thenReturn(9);

        assertThat(service.getDashboard(userId).streaks()).containsEntry(active.getId(), 9);
    }

    // ---- Per-area counts ----

    @Test
    void GivenUpgradesFiledUnderAnArea_WhenTheDashboardIsBuilt_ThenThatAreaCarriesItsTotals() {
        UUID areaId = UUID.randomUUID();
        HealthArea area = HealthArea.builder().id(areaId).userId(userId).name("Sleep").build();
        List<HealthUpgrade> upgrades = List.of(
                inArea(areaId, UpgradeStatus.ACTIVE),
                inArea(areaId, UpgradeStatus.COMPLETED),
                inArea(areaId, UpgradeStatus.PLANNED));
        when(upgradeQuery.findByUser(userId)).thenReturn(upgrades);
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(streakQuery.currentStreak(any())).thenReturn(0);
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of(area));

        DashboardView.AreaSummary summary = service.getDashboard(userId).areaSummaries().get(0);

        assertThat(summary.areaId()).isEqualTo(areaId);
        assertThat(summary.areaName()).isEqualTo("Sleep");
        assertThat(summary.totalUpgrades()).isEqualTo(3);
        assertThat(summary.activeCount()).isEqualTo(1);
        assertThat(summary.completedCount()).isEqualTo(1);
    }

    @Test
    void GivenAnAreaWithNoUpgradesFiledUnderIt_WhenTheDashboardIsBuilt_ThenItStillAppearsWithZeroes() {
        // An empty area is a real state — one just created, or one whose upgrades were all deleted —
        // and dropping it from the dashboard would make it look as though it had gone.
        HealthArea empty = HealthArea.builder().id(UUID.randomUUID()).userId(userId).name("Movement").build();
        when(upgradeQuery.findByUser(userId)).thenReturn(List.of());
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of(empty));

        DashboardView.AreaSummary summary = service.getDashboard(userId).areaSummaries().get(0);

        assertThat(summary.totalUpgrades()).isZero();
        assertThat(summary.activeCount()).isZero();
        assertThat(summary.completedCount()).isZero();
    }

    @Test
    void GivenUpgradesFiledUnderNoArea_WhenTheAreaCountsAreBuilt_ThenTheyAreLeftOutRatherThanGroupedUnderNull() {
        HealthArea area = HealthArea.builder().id(UUID.randomUUID()).userId(userId).name("Sleep").build();
        stubEmptyExceptUpgrades(List.of(upgrade(UpgradeStatus.PLANNED), upgrade(UpgradeStatus.PLANNED)));
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of(area));

        assertThat(service.getDashboard(userId).areaSummaries().get(0).totalUpgrades()).isZero();
    }

    private HealthUpgrade completedAt(LocalDate updatedAt) {
        return HealthUpgrade.builder()
                .id(UUID.randomUUID()).userId(userId).status(UpgradeStatus.COMPLETED)
                .updatedAt(updatedAt.atStartOfDay()).build();
    }

    private HealthUpgrade inArea(UUID areaId, UpgradeStatus status) {
        return HealthUpgrade.builder()
                .id(UUID.randomUUID()).userId(userId).areaId(areaId).status(status)
                .updatedAt(today.atStartOfDay()).build();
    }

    /** Stubs every port but the upgrade list, which is what most of these cases vary. */
    private void stubEmptyExceptUpgrades(List<HealthUpgrade> upgrades) {
        when(upgradeQuery.findByUser(userId)).thenReturn(upgrades);
        when(progressQuery.findByUserIdAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(healthAreaQuery.listByUser(userId)).thenReturn(List.of());
    }
}
