package com.healthupgrades.dashboard.application;

import com.healthupgrades.dashboard.api.DashboardDto;
import com.healthupgrades.healtharea.infrastructure.HealthAreaRepository;
import com.healthupgrades.tracking.domain.ProgressEntry;
import com.healthupgrades.tracking.domain.StreakCalculator;
import com.healthupgrades.tracking.infrastructure.ProgressEntryRepository;
import com.healthupgrades.upgrade.api.UpgradeDto;
import com.healthupgrades.upgrade.application.UpgradeService;
import com.healthupgrades.upgrade.domain.HealthUpgrade;
import com.healthupgrades.upgrade.domain.UpgradeStatus;
import com.healthupgrades.upgrade.infrastructure.UpgradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardAggregationServiceTest {

    @Mock UpgradeRepository upgradeRepository;
    @Mock ProgressEntryRepository progressRepository;
    @Mock HealthAreaRepository areaRepository;
    @Mock StreakCalculator streakCalculator;
    @Mock UpgradeService upgradeService;

    @InjectMocks DashboardAggregationService service;

    private final UUID userId = UUID.randomUUID();

    private HealthUpgrade upgrade(UpgradeStatus status) {
        return HealthUpgrade.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .status(status)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ProgressEntry entry(boolean completed) {
        return ProgressEntry.builder()
                .id(UUID.randomUUID())
                .upgradeId(UUID.randomUUID())
                .userId(userId)
                .date(LocalDate.now())
                .completed(completed)
                .build();
    }

    @Test
    void buildDashboard_countsByStatus_andComputesWeeklyRate() {
        List<HealthUpgrade> upgrades = List.of(
                upgrade(UpgradeStatus.ACTIVE),
                upgrade(UpgradeStatus.ACTIVE),
                upgrade(UpgradeStatus.PLANNED),
                upgrade(UpgradeStatus.COMPLETED));
        when(upgradeRepository.findByUserId(userId)).thenReturn(upgrades);
        when(upgradeService.toDtos(any())).thenAnswer(inv -> {
            List<HealthUpgrade> ups = inv.getArgument(0);
            return ups.stream().map(u -> dtoFor(u.getId())).toList();
        });
        // 2 of 4 weekly entries completed -> 50%
        when(progressRepository.findByUserIdAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(entry(true), entry(true), entry(false), entry(false)));
        when(progressRepository.findByUpgradeId(any())).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any())).thenReturn(4);
        when(areaRepository.findByUserId(userId)).thenReturn(List.of());

        DashboardDto dashboard = service.buildDashboard(userId);

        assertThat(dashboard.activeUpgrades()).hasSize(2);
        assertThat(dashboard.plannedUpgrades()).hasSize(1);
        assertThat(dashboard.recentlyCompleted()).hasSize(1);
        assertThat(dashboard.weeklyCompletionRate()).isEqualTo(50.0);
        // streaks are computed only for ACTIVE upgrades
        assertThat(dashboard.streaks()).hasSize(2);
    }

    private UpgradeDto dtoFor(UUID id) {
        return new UpgradeDto(id, userId, null, "t", null, null, null, null,
                null, null, null, null, null, false, 0L, null, LocalDateTime.now(), LocalDateTime.now());
    }
}
