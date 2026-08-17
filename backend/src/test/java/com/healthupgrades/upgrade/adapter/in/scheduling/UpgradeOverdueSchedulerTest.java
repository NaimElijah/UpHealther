package com.healthupgrades.upgrade.adapter.in.scheduling;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.upgrade.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

@ExtendWith(MockitoExtension.class)
class UpgradeOverdueSchedulerTest {

    @Mock UpgradeQuery upgradeQuery;
    @Mock DomainEventPublisher eventPublisher;

    /**
     * Deliberately fixed to a date well in the past, so a target date that is still "ahead" by this clock
     * is unambiguously behind by the wall clock. That gap is what
     * {@link #detectOverdueUpgrades_judgesAgainstTheInjectedClockNotTheWallClock()} exploits.
     */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2020-01-01T09:00:00Z"), ZoneOffset.UTC);
    private final LocalDate today = LocalDate.of(2020, 1, 1);

    private UpgradeOverdueScheduler scheduler;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new UpgradeOverdueScheduler(upgradeQuery, eventPublisher, fixedClock);
    }

    private HealthUpgrade activeUpgradeEnding(LocalDate targetEndDate) {
        return HealthUpgrade.builder()
                .id(upgradeId).userId(userId).title("Sleep early")
                .status(UpgradeStatus.ACTIVE).targetEndDate(targetEndDate).build();
    }

    @Test
    void detectOverdueUpgrades_pastTargetDate_publishesEvent() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(today.minusDays(1))));

        scheduler.detectOverdueUpgrades();

        ArgumentCaptor<UpgradeOverdueDetected> published = ArgumentCaptor.forClass(UpgradeOverdueDetected.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().upgradeId()).isEqualTo(upgradeId);
        assertThat(published.getValue().userId()).isEqualTo(userId);
    }

    @Test
    void detectOverdueUpgrades_targetDateStillAhead_publishesNothing() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(today.plusDays(5))));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void detectOverdueUpgrades_noTargetDate_publishesNothing() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(null)));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void detectOverdueUpgrades_targetDateIsToday_publishesNothing() {
        // Overdue means past the target date; the final day still counts as on time.
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(today)));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void detectOverdueUpgrades_judgesAgainstTheInjectedClockNotTheWallClock() {
        // A target date long past by any wall clock this runs on, but still ahead of the clock this job
        // was given. Reading the system clock would report it overdue; reading the injected one does not.
        // Both dates are fixed, so the test does not depend on when the suite runs.
        LocalDate wellPastByTheWallClockButAheadOfOurs = today.plusMonths(5);
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(wellPastByTheWallClockButAheadOfOurs)));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }
}
