package com.healthupgrades.notification.scheduling;

import com.healthupgrades.notification.domain.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.reminder.domain.Reminder;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import com.healthupgrades.upgrade.domain.HealthUpgrade;
import com.healthupgrades.upgrade.domain.UpgradeStatus;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    @Mock UpgradeRepositoryPort upgradeRepository;
    @Mock ProgressEntryRepositoryPort progressRepository;
    @Mock ReminderRepositoryPort reminderRepository;
    @Mock NotificationRepositoryPort notificationRepository;
    @Mock com.healthupgrades.notification.application.NotificationService notificationService;

    // Fixed clock -> deterministic time-based scheduling (09:00 UTC).
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-24T09:00:00Z"), ZoneOffset.UTC);
    private NotificationScheduler scheduler;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new NotificationScheduler(upgradeRepository, progressRepository, reminderRepository,
                notificationRepository, notificationService, fixedClock);
    }

    @Test
    void notifyOverdue_notifiesOncePerOverdueUpgrade() {
        HealthUpgrade overdue = HealthUpgrade.builder().id(upgradeId).userId(userId).title("Sleep early")
                .status(UpgradeStatus.ACTIVE).targetEndDate(LocalDate.now().minusDays(1)).build();
        HealthUpgrade notOverdue = HealthUpgrade.builder().id(UUID.randomUUID()).userId(userId).title("Walk")
                .status(UpgradeStatus.ACTIVE).build(); // no target date -> not overdue
        when(upgradeRepository.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(overdue, notOverdue));
        when(notificationRepository.existsByUserIdAndRelatedUpgradeIdAndType(any(), any(), any())).thenReturn(false);

        scheduler.notifyOverdue();

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_OVERDUE), any(), any(), any(), eq(upgradeId));
    }

    @Test
    void notifyOverdue_skipsWhenAlreadyNotified() {
        HealthUpgrade overdue = HealthUpgrade.builder().id(upgradeId).userId(userId).title("Sleep early")
                .status(UpgradeStatus.ACTIVE).targetEndDate(LocalDate.now().minusDays(1)).build();
        when(upgradeRepository.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(overdue));
        when(notificationRepository.existsByUserIdAndRelatedUpgradeIdAndType(any(), any(), any())).thenReturn(true);

        scheduler.notifyOverdue();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void dispatchReminders_dueNow_notifiesOwner() {
        // Reminder time matches the fixed clock (09:00); no day filter -> due now.
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(9, 0)).daysOfWeek(null).enabled(true).build();
        when(reminderRepository.findByEnabledTrue()).thenReturn(List.of(reminder));
        when(upgradeRepository.findAllById(List.of(upgradeId))).thenReturn(List.of(
                HealthUpgrade.builder().id(upgradeId).userId(userId).title("Meditate").build()));

        scheduler.dispatchReminders();

        verify(notificationService).create(eq(userId), eq(NotificationType.REMINDER), any(), any(), any(), eq(upgradeId));
    }

    @Test
    void dispatchReminders_notDue_doesNothing() {
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(7, 30)).daysOfWeek(null).enabled(true).build();
        when(reminderRepository.findByEnabledTrue()).thenReturn(List.of(reminder));

        scheduler.dispatchReminders();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }
}
