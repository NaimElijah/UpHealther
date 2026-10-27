package com.healthupgrades.upgrade.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.common.observability.JobMetrics;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Covers the overdue sweep: which active upgrades it announces, and that it announces nothing for those
 * still within their target date.
 *
 * <p>It also pins NFR-25, what a run records about itself: the timer and the ok and failed counters under
 * the job's name, and the one INFO line counting what was scanned and what was announced.
 *
 * <p>Driven by a fixed {@link java.time.Clock}, so "past its target date" means the same thing on every
 * run.
 */
@ExtendWith(MockitoExtension.class)
class UpgradeOverdueSchedulerTest {

    /** Asserted as a literal: it is a metric tag and a log field, and a renamed one breaks every alert on it. */
    private static final String JOB = "upgrade.overdue-scan";

    @Mock
    UpgradeQuery upgradeQuery;

    @Mock
    DomainEventPublisher eventPublisher;

    /**
     * Deliberately fixed to a date well in the past, so a target date that is still "ahead" by this clock
     * is unambiguously behind by the wall clock. That gap is what
     * {@link #GivenAFixedClock_WhenTheOverdueSweepRuns_ThenItJudgesAgainstThatClockNotTheWallClock()} exploits.
     */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2020-01-01T09:00:00Z"), ZoneOffset.UTC);

    private final LocalDate today = LocalDate.of(2020, 1, 1);

    private UpgradeOverdueScheduler scheduler;

    private final UUID userId = UUID.randomUUID();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** A real one, not a mock: JobMetrics.timed runs the job, so a stub would run nothing. */
    private final JobMetrics jobMetrics = new JobMetrics(meterRegistry);

    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new UpgradeOverdueScheduler(upgradeQuery, eventPublisher, jobMetrics, fixedClock);
    }

    private HealthUpgrade activeUpgradeEnding(LocalDate targetEndDate) {
        return HealthUpgrade.builder()
                .id(upgradeId)
                .userId(userId)
                .title("Sleep early")
                .status(UpgradeStatus.ACTIVE)
                .targetEndDate(targetEndDate)
                .build();
    }

    @Test
    void GivenAnActiveUpgradePastItsTargetDate_WhenTheOverdueSweepRuns_ThenItIsAnnounced() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(today.minusDays(1))));

        scheduler.detectOverdueUpgrades();

        ArgumentCaptor<UpgradeOverdueDetected> published = ArgumentCaptor.forClass(UpgradeOverdueDetected.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().upgradeId()).isEqualTo(upgradeId);
        assertThat(published.getValue().userId()).isEqualTo(userId);
    }

    @Test
    void GivenAnActiveUpgradePastItsTargetDate_WhenTheOverdueSweepRuns_ThenTheAnnouncementNamesTheDateMissed() {
        // BR-11 announces once per target date, so the listener needs to know which date this is about.
        LocalDate missed = today.minusDays(3);
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgradeEnding(missed)));

        scheduler.detectOverdueUpgrades();

        ArgumentCaptor<UpgradeOverdueDetected> published = ArgumentCaptor.forClass(UpgradeOverdueDetected.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().targetEndDate()).isEqualTo(missed);
    }

    @Test
    void GivenATargetDateStillAhead_WhenTheOverdueSweepRuns_ThenNothingIsAnnounced() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(today.plusDays(5))));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenNoTargetDate_WhenTheOverdueSweepRuns_ThenNothingIsAnnounced() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgradeEnding(null)));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenATargetDateOfToday_WhenTheOverdueSweepRuns_ThenNothingIsAnnounced() {
        // Overdue means past the target date; the final day still counts as on time.
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(activeUpgradeEnding(today)));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenAFixedClock_WhenTheOverdueSweepRuns_ThenItJudgesAgainstThatClockNotTheWallClock() {
        // A target date long past by any wall clock this runs on, but still ahead of the clock this job
        // was given. Reading the system clock would report it overdue; reading the injected one does not.
        // Both dates are fixed, so the test does not depend on when the suite runs.
        LocalDate wellPastByTheWallClockButAheadOfOurs = today.plusMonths(5);
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(wellPastByTheWallClockButAheadOfOurs)));

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenOverdueUpgradesEitherSideOfOneOnTime_WhenTheOverdueSweepRuns_ThenEachOverdueOneIsAnnounced() {
        // A sweep that stopped at its first find, or skipped the rest after an on-time one, would still
        // pass every single-upgrade case above.
        HealthUpgrade first = activeUpgrade(UUID.randomUUID(), today.minusDays(1));
        HealthUpgrade ahead = activeUpgrade(UUID.randomUUID(), today.plusDays(1));
        HealthUpgrade last = activeUpgrade(UUID.randomUUID(), today.minusDays(9));
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of(first, ahead, last));

        scheduler.detectOverdueUpgrades();

        ArgumentCaptor<UpgradeOverdueDetected> published = ArgumentCaptor.forClass(UpgradeOverdueDetected.class);
        verify(eventPublisher, times(2)).publish(published.capture());
        assertThat(published.getAllValues())
                .extracting(UpgradeOverdueDetected::upgradeId)
                .containsExactly(first.getId(), last.getId());
    }

    private HealthUpgrade activeUpgrade(UUID id, LocalDate targetEndDate) {
        return HealthUpgrade.builder()
                .id(id)
                .userId(userId)
                .title("Sleep early")
                .status(UpgradeStatus.ACTIVE)
                .targetEndDate(targetEndDate)
                .build();
    }

    // ---- NFR-25: what a run records about itself ----

    @Test
    void GivenOneOverdueAndOneOnTimeUpgrade_WhenTheOverdueSweepRuns_ThenItsLineCountsBothScannedAndOneAnnounced() {
        // Counts that differ, so a line reporting the wrong one of them, or a constant, says so.
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE))
                .thenReturn(List.of(activeUpgradeEnding(today.minusDays(1)), activeUpgradeEnding(today.plusDays(1))));

        List<ILoggingEvent> logged = logsFromScheduler(scheduler::detectOverdueUpgrades);

        assertThat(logged)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("job=" + JOB, "scanned=2", "announced=1");
    }

    @Test
    void GivenASweep_WhenItFinishes_ThenTheRunIsTimedAndCountedAsOkUnderItsJobName() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenReturn(List.of());

        scheduler.detectOverdueUpgrades();

        assertThat(runs("ok")).isEqualTo(1);
        assertThat(meterRegistry
                        .get("scheduled.job.duration")
                        .tag("job", JOB)
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void GivenTheQueryFails_WhenTheOverdueSweepRuns_ThenTheFaultEscapesAndTheRunIsCountedAsFailed() {
        when(upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(scheduler::detectOverdueUpgrades).isInstanceOf(IllegalStateException.class);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("ok")).isZero();
    }

    private double runs(String outcome) {
        return meterRegistry.find("scheduled.job.runs").tag("job", JOB).tag("outcome", outcome).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private static List<ILoggingEvent> logsFromScheduler(Runnable job) {
        Logger logger = (Logger) LoggerFactory.getLogger(UpgradeOverdueScheduler.class);
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
