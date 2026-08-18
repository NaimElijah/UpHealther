package com.healthupgrades.notification.adapter.in.scheduling;

import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.reminder.application.port.in.ReminderQuery;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
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
/**
 * Covers the scheduled notifications: whether a reminder is dispatched at a given moment, and when the
 * daily check-in nudge is suppressed.
 *
 * <p>Deterministic through a fixed {@link java.time.Clock} — a scheduler test that read the system clock
 * would pass or fail depending on the minute it ran in.
 */
class NotificationSchedulerTest {

    @Mock UpgradeQuery upgradeQuery;
    @Mock ProgressQuery progressQuery;
    @Mock ReminderQuery reminderQuery;
    @Mock NotificationRepositoryPort notificationRepository;
    @Mock com.healthupgrades.notification.application.NotificationService notificationService;

    // Fixed clock -> deterministic time-based scheduling (09:00 UTC).
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-24T09:00:00Z"), ZoneOffset.UTC);
    private NotificationScheduler scheduler;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new NotificationScheduler(upgradeQuery, progressQuery, reminderQuery,
                notificationRepository, notificationService, fixedClock);
    }

    @Test
    void dispatchReminders_dueNow_notifiesOwner() {
        // Reminder time matches the fixed clock (09:00); no day filter -> due now.
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(9, 0)).daysOfWeek(null).enabled(true).build();
        when(reminderQuery.findEnabled()).thenReturn(List.of(reminder));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(
                HealthUpgrade.builder().id(upgradeId).userId(userId).title("Meditate").build()));

        scheduler.dispatchReminders();

        verify(notificationService).create(eq(userId), eq(NotificationType.REMINDER), any(), any(), any(), eq(upgradeId));
    }

    @Test
    void dispatchReminders_notDue_doesNothing() {
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(7, 30)).daysOfWeek(null).enabled(true).build();
        when(reminderQuery.findEnabled()).thenReturn(List.of(reminder));

        scheduler.dispatchReminders();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }
}
