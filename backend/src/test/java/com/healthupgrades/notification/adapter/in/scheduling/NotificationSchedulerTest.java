package com.healthupgrades.notification.adapter.in.scheduling;

import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.reminder.application.port.in.ReminderQuery;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers FR-30 (a user with active upgrades and nothing logged is nudged once a day) and FR-31 (a
 * reminder fires at its configured time and day).
 *
 * <p>Deterministic through a fixed {@link java.time.Clock} — a scheduler test that read the system clock
 * would pass or fail depending on the minute it ran in.
 *
 * <p>"Once a day" is the part worth stating: nothing stops either cron from being configured to run more
 * often than its default, so the guard is what makes the requirement true rather than the schedule.
 */
@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    @Mock UpgradeQuery upgradeQuery;
    @Mock ProgressQuery progressQuery;
    @Mock ReminderQuery reminderQuery;
    @Mock NotificationRepositoryPort notificationRepository;
    @Mock com.healthupgrades.notification.application.NotificationService notificationService;

    // Fixed clock -> deterministic time-based scheduling (09:00 UTC on a Wednesday).
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-24T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 24);
    private NotificationScheduler scheduler;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new NotificationScheduler(upgradeQuery, progressQuery, reminderQuery,
                notificationRepository, notificationService, fixedClock);
    }

    @Test
    void GivenAReminderDueNow_WhenRemindersAreDispatched_ThenItsOwnerIsNotified() {
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
    void GivenNoReminderIsDue_WhenRemindersAreDispatched_ThenNobodyIsNotified() {
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(7, 30)).daysOfWeek(null).enabled(true).build();
        when(reminderQuery.findEnabled()).thenReturn(List.of(reminder));

        scheduler.dispatchReminders();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    // ---- FR-30: the daily check-in nudge ----

    @Test
    void GivenAUserWithActiveUpgradesAndNothingLoggedToday_WhenTheCheckinSweepRuns_ThenTheyAreNudged() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgrade()));
        when(notificationRepository.existsByUserIdAndTypeAndCreatedAtAfter(
                eq(userId), eq(NotificationType.CHECKIN_REMINDER), any())).thenReturn(false);
        when(progressQuery.findByUserIdAndDate(eq(userId), any())).thenReturn(List.of());

        scheduler.notifyDailyCheckin();

        verify(notificationService).create(eq(userId), eq(NotificationType.CHECKIN_REMINDER),
                any(), any(), any(), eq(null));
    }

    @Test
    void GivenTheUserHasAlreadyBeenNudgedSinceMidnight_WhenTheCheckinSweepRunsAgain_ThenTheyAreNotNudgedTwice() {
        // FR-30 says "once a day", and the cron is configurable — so the guard, not the schedule, is
        // what makes that true.
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgrade()));
        when(notificationRepository.existsByUserIdAndTypeAndCreatedAtAfter(
                eq(userId), eq(NotificationType.CHECKIN_REMINDER), any())).thenReturn(true);

        scheduler.notifyDailyCheckin();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void GivenTheUserHasAlreadyLoggedSomethingToday_WhenTheCheckinSweepRuns_ThenTheyAreNotNudged() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgrade()));
        when(notificationRepository.existsByUserIdAndTypeAndCreatedAtAfter(
                eq(userId), eq(NotificationType.CHECKIN_REMINDER), any())).thenReturn(false);
        when(progressQuery.findByUserIdAndDate(eq(userId), any()))
                .thenReturn(List.of(ProgressEntry.builder().id(UUID.randomUUID()).userId(userId)
                        .upgradeId(upgradeId).date(TODAY).completed(true).build()));

        scheduler.notifyDailyCheckin();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void GivenNobodyHasAnActiveUpgrade_WhenTheCheckinSweepRuns_ThenNobodyIsNudgedAndNothingIsLookedUp() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of());

        scheduler.notifyDailyCheckin();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(progressQuery, never()).findByUserIdAndDate(any(), any());
    }

    @Test
    void GivenTheCheckinSweepRuns_WhenTodayIsDecided_ThenItComesFromTheInjectedClock() {
        // NFR-15. The "already nudged" guard is a since-midnight window, so a wall-clock read here would
        // make the test's verdict depend on the hour it ran in.
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgrade()));
        when(notificationRepository.existsByUserIdAndTypeAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(true);

        scheduler.notifyDailyCheckin();

        verify(notificationRepository).existsByUserIdAndTypeAndCreatedAtAfter(
                userId, NotificationType.CHECKIN_REMINDER, TODAY.atStartOfDay());
    }

    // ---- FR-31: the day filter ----

    @Test
    void GivenAReminderDueAtThisTimeButOnAnotherDay_WhenRemindersAreDispatched_ThenNobodyIsNotified() {
        // The fixed clock is a Wednesday; this reminder only fires on Mondays.
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(9, 0)).daysOfWeek("MON").enabled(true).build();
        when(reminderQuery.findEnabled()).thenReturn(List.of(reminder));

        scheduler.dispatchReminders();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(upgradeQuery, never()).findAllById(any());
    }

    @Test
    void GivenAReminderDueOnThisDay_WhenRemindersAreDispatched_ThenItsOwnerIsNotified() {
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(9, 0)).daysOfWeek("WED").enabled(true).build();
        when(reminderQuery.findEnabled()).thenReturn(List.of(reminder));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));

        scheduler.dispatchReminders();

        verify(notificationService).create(eq(userId), eq(NotificationType.REMINDER), any(), any(), any(),
                eq(upgradeId));
    }

    @Test
    void GivenSeveralRemindersAreDue_WhenRemindersAreDispatched_ThenTheirUpgradesAreLoadedInOneBatch() {
        // NFR-14. This sweep runs every minute; a lookup per reminder is the cost that grows with the
        // number of users.
        UUID otherUpgradeId = UUID.randomUUID();
        when(reminderQuery.findEnabled()).thenReturn(List.of(
                Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                        .reminderTime(LocalTime.of(9, 0)).enabled(true).build(),
                Reminder.builder().id(UUID.randomUUID()).upgradeId(otherUpgradeId)
                        .reminderTime(LocalTime.of(9, 0)).enabled(true).build()));
        when(upgradeQuery.findAllById(List.of(upgradeId, otherUpgradeId)))
                .thenReturn(List.of(activeUpgrade()));

        scheduler.dispatchReminders();

        verify(upgradeQuery, times(1)).findAllById(any());
    }

    @Test
    void GivenADueReminderWhoseUpgradeHasBeenDeleted_WhenRemindersAreDispatched_ThenItIsSkippedRatherThanFailing() {
        // A reminder can outlive its upgrade. Dereferencing the missing one would throw inside a
        // scheduled job, where nothing is waiting to handle it.
        when(reminderQuery.findEnabled()).thenReturn(List.of(
                Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                        .reminderTime(LocalTime.of(9, 0)).enabled(true).build()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of());

        scheduler.dispatchReminders();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    private HealthUpgrade activeUpgrade() {
        return HealthUpgrade.builder().id(upgradeId).userId(userId).title("Meditate")
                .status(UpgradeStatus.ACTIVE).build();
    }
}
