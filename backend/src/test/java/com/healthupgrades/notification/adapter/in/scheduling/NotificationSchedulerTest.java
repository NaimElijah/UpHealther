package com.healthupgrades.notification.adapter.in.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.reminder.application.port.in.ReminderQuery;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.common.observability.JobMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers FR-30 (a user with active upgrades and nothing logged is nudged once a day), FR-31 (a
 * reminder fires at its configured time and day) and BR-23 (only while its upgrade is active).
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
    /** A real one, not a mock: JobMetrics.timed runs the job, so a stub would run nothing. */
    private final JobMetrics jobMetrics = new JobMetrics(new SimpleMeterRegistry());

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    /** The WARN a reminder that outlived its upgrade raises; see {@code dispatchReminders}. */
    private static final Predicate<ILoggingEvent> ORPHANED_WARNING = event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().contains("reminder.orphaned");

    @BeforeEach
    void setUp() {
        scheduler = new NotificationScheduler(upgradeQuery, progressQuery, reminderQuery,
                notificationRepository, notificationService, jobMetrics, fixedClock);
    }

    @Test
    void GivenAReminderDueNow_WhenRemindersAreDispatched_ThenItsOwnerIsNotified() {
        // Reminder time matches the fixed clock (09:00); no day filter -> due now.
        Reminder reminder = Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(9, 0)).daysOfWeek(null).enabled(true).build();
        when(reminderQuery.findEnabled()).thenReturn(List.of(reminder));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));

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
        guards(Set.of(), Set.of());

        scheduler.notifyDailyCheckin();

        verify(notificationService).create(eq(userId), eq(NotificationType.CHECKIN_REMINDER),
                any(), any(), any(), eq(null));
    }

    @Test
    void GivenTheUserHasAlreadyBeenNudgedSinceMidnight_WhenTheCheckinSweepRunsAgain_ThenTheyAreNotNudgedTwice() {
        // FR-30 says "once a day", and the cron is configurable — so the guard, not the schedule, is
        // what makes that true.
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgrade()));
        guards(Set.of(userId), Set.of());

        scheduler.notifyDailyCheckin();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void GivenTheUserHasAlreadyLoggedSomethingToday_WhenTheCheckinSweepRuns_ThenTheyAreNotNudged() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgrade()));
        guards(Set.of(), Set.of(userId));

        scheduler.notifyDailyCheckin();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void GivenSeveralUsersWithActiveUpgrades_WhenTheCheckinSweepRuns_ThenEachGuardIsReadOnceForAllOfThem() {
        // NFR-14 (#99). Both guards used to be asked once per user, so the sweep's cost grew with every
        // user who had something running.
        UUID nudgedAlready = UUID.randomUUID();
        UUID loggedAlready = UUID.randomUUID();
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(
                activeUpgradeOf(nudgedAlready), activeUpgradeOf(loggedAlready), activeUpgrade()));
        guards(Set.of(nudgedAlready), Set.of(loggedAlready));

        scheduler.notifyDailyCheckin();

        verify(notificationRepository, times(1)).findUserIdsNotifiedAfter(any(), any());
        verify(progressQuery, times(1)).findUserIdsWithEntriesOn(any());
        verify(notificationService).create(eq(userId), eq(NotificationType.CHECKIN_REMINDER),
                any(), any(), any(), eq(null));
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void GivenNobodyHasAnActiveUpgrade_WhenTheCheckinSweepRuns_ThenNobodyIsNudgedAndNothingIsLookedUp() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of());

        scheduler.notifyDailyCheckin();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(notificationRepository, never()).findUserIdsNotifiedAfter(any(), any());
        verify(progressQuery, never()).findUserIdsWithEntriesOn(any());
    }

    @Test
    void GivenTheCheckinSweepRuns_WhenTodayIsDecided_ThenItComesFromTheInjectedClock() {
        // NFR-15. The "already nudged" guard is a since-midnight window, so a wall-clock read here would
        // make the test's verdict depend on the hour it ran in.
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgrade()));

        scheduler.notifyDailyCheckin();

        verify(notificationRepository).findUserIdsNotifiedAfter(NotificationType.CHECKIN_REMINDER, TODAY.atStartOfDay());
        verify(progressQuery).findUserIdsWithEntriesOn(TODAY);
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

        List<ILoggingEvent> logged = logsFromScheduler(scheduler::dispatchReminders);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        assertThat(logged).anyMatch(ORPHANED_WARNING);
    }

    // ---- BR-23: only an active upgrade reminds ----

    @ParameterizedTest
    @EnumSource(value = UpgradeStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void GivenADueReminderOnAnUpgradeThatIsNotActive_WhenRemindersAreDispatched_ThenNobodyIsNotified(
            UpgradeStatus status) {
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(upgradeIn(status)));

        scheduler.dispatchReminders();

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void GivenADueReminderOnAPausedUpgrade_WhenRemindersAreDispatched_ThenTheRunCountsItSilencedNotOrphaned() {
        // Silenced by its upgrade's status is the rule working, not an inconsistency: a WARN here would
        // fire every minute a paused upgrade's reminder came due. The run's own line is what says the
        // rule was applied, so it is asserted positively; an absent WARN alone would also pass a run
        // that logged nothing at all.
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(upgradeIn(UpgradeStatus.PAUSED)));

        List<ILoggingEvent> logged = logsFromScheduler(scheduler::dispatchReminders);

        assertThat(logged).noneMatch(ORPHANED_WARNING);
        assertThat(logged).filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("due=1", "fired=0", "silenced=1");
    }

    /** A reminder due at the fixed clock's minute on any day, hanging off {@link #upgradeId}. */
    private Reminder dueReminder() {
        return Reminder.builder().id(UUID.randomUUID()).upgradeId(upgradeId)
                .reminderTime(LocalTime.of(9, 0)).enabled(true).build();
    }

    private HealthUpgrade upgradeIn(UpgradeStatus status) {
        return HealthUpgrade.builder().id(upgradeId).userId(userId).title("Meditate").status(status).build();
    }

    private HealthUpgrade activeUpgrade() {
        return upgradeIn(UpgradeStatus.ACTIVE);
    }

    private HealthUpgrade activeUpgradeOf(UUID owner) {
        return HealthUpgrade.builder().id(UUID.randomUUID()).userId(owner).title("Stretch")
                .status(UpgradeStatus.ACTIVE).build();
    }

    /** Stubs the check-in sweep's two guards: who was nudged since midnight, and who logged today. */
    private void guards(Set<UUID> nudgedSinceMidnight, Set<UUID> loggedToday) {
        when(notificationRepository.findUserIdsNotifiedAfter(NotificationType.CHECKIN_REMINDER, TODAY.atStartOfDay()))
                .thenReturn(nudgedSinceMidnight);
        when(progressQuery.findUserIdsWithEntriesOn(TODAY)).thenReturn(loggedToday);
    }

    /** Collects what {@link NotificationScheduler} logs while {@code job} runs. */
    private static List<ILoggingEvent> logsFromScheduler(Runnable job) {
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            job.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
