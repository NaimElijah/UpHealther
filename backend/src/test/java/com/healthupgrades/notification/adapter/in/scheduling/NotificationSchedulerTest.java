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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLIntegrityConstraintViolationException;
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
import static org.assertj.core.api.Assertions.catchThrowable;
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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    /** A real one, not a mock: JobMetrics.timed runs the job, so a stub would run nothing. */
    private final JobMetrics jobMetrics = new JobMetrics(meterRegistry);

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    /** The WARN a reminder that outlived its upgrade raises; see {@code dispatchReminders}. */
    private static final Predicate<ILoggingEvent> ORPHANED_WARNING = event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().contains("reminder.orphaned");

    /** The WARN a notification that could not be created raises; see {@code Deliveries}. */
    private static final Predicate<ILoggingEvent> CREATE_FAILED_WARNING = event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().contains("notification.create-failed");

    /** The run's own INFO line: what the sweep found and what it did. */
    private static final Predicate<ILoggingEvent> RUN_LINE = event -> event.getLevel() == Level.INFO;

    /**
     * A save failure whose message quotes a row, as a constraint violation's does. Nothing of it but its
     * type may reach a log line (NFR-6).
     */
    private static DataIntegrityViolationException saveFailure() {
        return new DataIntegrityViolationException("Key (title)=(Meditate) violates a constraint");
    }

    /** What a {@code @Transactional} call throws when the pool cannot hand it a connection. */
    private static CannotCreateTransactionException unreachable() {
        return new CannotCreateTransactionException("Could not open JPA EntityManager for transaction");
    }

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

    // ---- NFR-50 (#136): one failed save does not stop the rest of a sweep ----

    @Test
    void GivenTheFirstOfTwoDueRemindersFailsToSave_WhenRemindersAreDispatched_ThenTheSecondIsStillFired() {
        // A minute does not repeat, so a reminder the sweep never reached is never sent.
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder(), dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(saveFailure())
                .thenReturn(null);

        catchThrowable(scheduler::dispatchReminders);

        verify(notificationService, times(2)).create(eq(userId), eq(NotificationType.REMINDER), any(), any(), any(),
                eq(upgradeId));
    }

    @Test
    void GivenTwoDueRemindersFailToSave_WhenRemindersAreDispatched_ThenTheRunFailsWithTheFirstFailure() {
        // The first failure is the one that says why; whatever followed it is most likely the same cause.
        RuntimeException first = saveFailure();
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder(), dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(first)
                .thenThrow(saveFailure());

        Throwable thrown = catchThrowable(scheduler::dispatchReminders);

        assertThat(thrown).isSameAs(first);
    }

    @Test
    void GivenADueReminderFailsToSave_WhenRemindersAreDispatched_ThenTheRunIsCountedFailed() {
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any())).thenThrow(saveFailure());

        catchThrowable(scheduler::dispatchReminders);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("ok")).isZero();
    }

    @Test
    void GivenADueReminderFailsToSave_WhenRemindersAreDispatched_ThenAWarningNamesItByIdsAndTypeOnly() {
        // No stack trace and no message: the one stack trace belongs to the scheduler's ERROR line, and a
        // driver's message can quote the row it refused (NFR-6).
        Reminder reminder = dueReminder();
        when(reminderQuery.findEnabled()).thenReturn(List.of(reminder));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any())).thenThrow(saveFailure());

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::dispatchReminders));

        List<ILoggingEvent> warnings = logged.stream().filter(CREATE_FAILED_WARNING).toList();
        assertThat(warnings).hasSize(1);
        ILoggingEvent warning = warnings.get(0);
        assertThat(warning.getFormattedMessage())
                .contains("job=notification.reminder-dispatch", "reminderId=" + reminder.getId(),
                        "upgradeId=" + upgradeId, "userId=" + userId, "exception=DataIntegrityViolationException")
                .doesNotContain("Meditate", "violates");
        assertThat(warning.getThrowableProxy()).isNull();
    }

    @Test
    void GivenASaveFailureWithADriverCause_WhenRemindersAreDispatched_ThenTheWarningNamesTheCauseByTypeOnly() {
        // Only the first failure's stack trace reaches the run's ERROR line. For every later one, the
        // root cause's type is what tells a constraint from a dropped connection - without its message,
        // which quotes the row just as the wrapper's does.
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLIntegrityConstraintViolationException("Failing row contains (Meditate)")));

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::dispatchReminders));

        assertThat(logged).filteredOn(CREATE_FAILED_WARNING)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("exception=DataIntegrityViolationException",
                        "rootCause=SQLIntegrityConstraintViolationException")
                .doesNotContain("Meditate", "Failing row");
    }

    @Test
    void GivenADueReminderFailsToSave_WhenRemindersAreDispatched_ThenTheRunLineCountsItFailedNotFired() {
        // The run's line is what says how many were lost; it used to be skipped along with the rest.
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder(), dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(saveFailure())
                .thenReturn(null);

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::dispatchReminders));

        assertThat(logged).filteredOn(RUN_LINE)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("due=2", "fired=1", "failed=1", "silenced=0", "skipped=0");
    }

    @Test
    void GivenAFailedReminderAndAnOrphanedOne_WhenRemindersAreDispatched_ThenTheOrphanWarningIsStillWritten() {
        UUID deletedUpgradeId = UUID.randomUUID();
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder(), dueReminderOn(deletedUpgradeId)));
        when(upgradeQuery.findAllById(List.of(upgradeId, deletedUpgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any())).thenThrow(saveFailure());

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::dispatchReminders));

        assertThat(logged).anyMatch(ORPHANED_WARNING);
    }

    @Test
    void GivenThreeAdjacentRemindersFailToSave_WhenRemindersAreDispatched_ThenTheOnesAfterThemAreStillFired() {
        // Bad rows can sit next to each other - nothing orders the sweep - and however many there are,
        // they are a fact about those rows, not about the database. Only an outage stops the sweep.
        when(reminderQuery.findEnabled()).thenReturn(
                List.of(dueReminder(), dueReminder(), dueReminder(), dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(saveFailure())
                .thenThrow(saveFailure())
                .thenThrow(saveFailure())
                .thenReturn(null);

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::dispatchReminders));

        verify(notificationService, times(4)).create(any(), any(), any(), any(), any(), any());
        assertThat(logged).filteredOn(RUN_LINE)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("due=4", "fired=1", "failed=3", "skipped=0");
    }

    @Test
    void GivenTheDatabaseCannotBeReached_WhenRemindersAreDispatched_ThenTheSweepStopsAtTheFirstSuchFailure() {
        // A transaction that cannot begin means the pool waited out its connection timeout. Every further
        // attempt would wait it out again, on the one thread all four jobs share, and push the next
        // reminder run past its minute.
        when(reminderQuery.findEnabled()).thenReturn(List.of(dueReminder(), dueReminder(), dueReminder()));
        when(upgradeQuery.findAllById(List.of(upgradeId))).thenReturn(List.of(activeUpgrade()));
        when(notificationService.create(any(), any(), any(), any(), any(), any())).thenThrow(unreachable());

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::dispatchReminders));

        verify(notificationService, times(1)).create(any(), any(), any(), any(), any(), any());
        assertThat(logged).filteredOn(RUN_LINE)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("due=3", "fired=0", "failed=1", "skipped=2");
    }

    @Test
    void GivenTheDatabaseCannotBeReached_WhenTheCheckinSweepRuns_ThenTheSweepStopsAtTheFirstSuchFailure() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgradeOf(UUID.randomUUID()),
                activeUpgrade()));
        guards(Set.of(), Set.of());
        when(notificationService.create(any(), any(), any(), any(), any(), any())).thenThrow(unreachable());

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::notifyDailyCheckin));

        verify(notificationService, times(1)).create(any(), any(), any(), any(), any(), any());
        assertThat(logged).filteredOn(RUN_LINE)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("nudged=0", "failed=1", "skipped=1");
    }

    @Test
    void GivenOneUsersNudgeFailsToSave_WhenTheCheckinSweepRuns_ThenTheOthersAreStillNudgedAndTheRunFails() {
        UUID failing = UUID.randomUUID();
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgradeOf(failing),
                activeUpgrade()));
        guards(Set.of(), Set.of());
        RuntimeException failure = saveFailure();
        nudgeFailsFor(failing, failure);

        Throwable thrown = catchThrowable(scheduler::notifyDailyCheckin);

        verify(notificationService).create(eq(userId), eq(NotificationType.CHECKIN_REMINDER), any(), any(), any(),
                eq(null));
        assertThat(thrown).isSameAs(failure);
        assertThat(runs("failed")).isEqualTo(1);
    }

    @Test
    void GivenANudgeFailsToSave_WhenTheCheckinSweepRuns_ThenTheRunLineCountsItFailed() {
        UUID failing = UUID.randomUUID();
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgradeOf(failing),
                activeUpgrade()));
        guards(Set.of(), Set.of());
        nudgeFailsFor(failing, saveFailure());

        List<ILoggingEvent> logged = logsFromScheduler(() -> catchThrowable(scheduler::notifyDailyCheckin));

        assertThat(logged).filteredOn(CREATE_FAILED_WARNING)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("job=notification.daily-checkin", "userId=" + failing);
        assertThat(logged).filteredOn(RUN_LINE)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("nudged=1", "failed=1", "skipped=0");
    }

    /** A reminder due at the fixed clock's minute on any day, hanging off {@link #upgradeId}. */
    private Reminder dueReminder() {
        return dueReminderOn(upgradeId);
    }

    private Reminder dueReminderOn(UUID upgrade) {
        return Reminder.builder().id(UUID.randomUUID()).upgradeId(upgrade)
                .reminderTime(LocalTime.of(9, 0)).enabled(true).build();
    }

    /** How many runs of either job ended with {@code outcome}. */
    private double runs(String outcome) {
        return meterRegistry.find("scheduled.job.runs")
                .tag("outcome", outcome).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
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

    /**
     * Makes the nudge to {@code failing} throw {@code failure} and the nudge to {@link #userId} succeed.
     *
     * <p>Stubbed by user, not by call order, because the sweep walks a {@code HashMap}. Both users are
     * stubbed because strict stubs answer a call that matches no stub with {@code PotentialStubbingProblem},
     * and the sweep, which carries on past any {@code RuntimeException}, would count that as a second
     * failed save whenever the map happened to reach {@link #userId} first.
     */
    private void nudgeFailsFor(UUID failing, RuntimeException failure) {
        when(notificationService.create(eq(failing), any(), any(), any(), any(), any())).thenThrow(failure);
        when(notificationService.create(eq(userId), any(), any(), any(), any(), any())).thenReturn(null);
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
