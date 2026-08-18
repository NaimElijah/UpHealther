package com.healthupgrades.dashboard.application;

import com.healthupgrades.dashboard.application.port.in.DashboardView;
import com.healthupgrades.healtharea.application.port.in.HealthAreaQuery;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * Covers the dashboard's aggregation rules: which upgrade lands in which bucket, and how the weekly
 * completion rate is computed.
 *
 * <p>Every collaborator is a mocked inbound port, so what is under test is the composition itself —
 * the bucketing and arithmetic — rather than any other context's behaviour.
 */
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
    void getDashboard_countsByStatus_andComputesWeeklyRate() {
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
}
