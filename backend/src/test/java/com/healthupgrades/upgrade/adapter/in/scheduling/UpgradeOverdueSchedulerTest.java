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
import com.healthupgrades.upgrade.application.UpgradeOverdueService;
import com.healthupgrades.upgrade.application.port.in.UpgradeSweepQuery;
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
 * Covers the overdue sweep: that it announces each candidate the query returns, and nothing when it returns
 * none. The stubs return only what the real query can, active upgrades past their target date; which upgrades
 * those are is {@code UpgradePersistenceIT}'s to pin, against PostgreSQL.
 *
 * <p>It also pins NFR-25, what a run records about itself: the timer and the ok and failed counters under
 * the job's name, and the one INFO line counting what was announced.
 *
 * <p>Driven by a fixed {@link java.time.Clock}, so "past its target date" means the same thing on every
 * run. Driven through the scheduler, over the real {@link UpgradeOverdueService} that holds the sweep since
 * #338, so the trigger, its metrics and the use case are covered together; only the ports are mocked.
 */
@ExtendWith(MockitoExtension.class)
class UpgradeOverdueSchedulerTest {

    /** Asserted as a literal: it is a metric tag and a log field, and a renamed one breaks every alert on it. */
    private static final String JOB = "upgrade.overdue-scan";

    @Mock
    UpgradeSweepQuery upgradeQuery;

    @Mock
    DomainEventPublisher eventPublisher;

    /**
     * Deliberately fixed to a date well in the past, so its day is never the wall clock's. That gap is what
     * {@link #GivenAFixedClock_WhenTheOverdueSweepRuns_ThenItAsksForTheCandidatesOfThatClocksDay()} exploits.
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
        scheduler = new UpgradeOverdueScheduler(
                new UpgradeOverdueService(upgradeQuery, eventPublisher), jobMetrics, fixedClock);
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
        when(upgradeQuery.findActivePastTargetDate(today)).thenReturn(List.of(activeUpgradeEnding(today.minusDays(1))));

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
        when(upgradeQuery.findActivePastTargetDate(today)).thenReturn(List.of(activeUpgradeEnding(missed)));

        scheduler.detectOverdueUpgrades();

        ArgumentCaptor<UpgradeOverdueDetected> published = ArgumentCaptor.forClass(UpgradeOverdueDetected.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().targetEndDate()).isEqualTo(missed);
    }

    @Test
    void GivenNoCandidates_WhenTheOverdueSweepRuns_ThenNothingIsAnnounced() {
        // An upgrade on time, due today, with no target date or not ACTIVE is never a candidate:
        // UpgradePersistenceIT pins each out of the query, against PostgreSQL.
        when(upgradeQuery.findActivePastTargetDate(today)).thenReturn(List.of());

        scheduler.detectOverdueUpgrades();

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenAFixedClock_WhenTheOverdueSweepRuns_ThenItAsksForTheCandidatesOfThatClocksDay() {
        // The clock is fixed years in the past, so a sweep that read the wall clock would ask for another
        // day, and the strict stub, which answers only for the injected clock's day, would refuse it.
        when(upgradeQuery.findActivePastTargetDate(today)).thenReturn(List.of(activeUpgradeEnding(today.minusDays(1))));

        scheduler.detectOverdueUpgrades();

        verify(upgradeQuery).findActivePastTargetDate(today);
        verify(eventPublisher).publish(any());
    }

    @Test
    void GivenSeveralOverdueUpgrades_WhenTheOverdueSweepRuns_ThenEachIsAnnouncedInTurn() {
        // A sweep that stopped at its first find would still pass every single-upgrade case above.
        HealthUpgrade first = activeUpgrade(UUID.randomUUID(), today.minusDays(1));
        HealthUpgrade second = activeUpgrade(UUID.randomUUID(), today.minusDays(4));
        HealthUpgrade last = activeUpgrade(UUID.randomUUID(), today.minusDays(9));
        when(upgradeQuery.findActivePastTargetDate(today)).thenReturn(List.of(first, second, last));

        scheduler.detectOverdueUpgrades();

        ArgumentCaptor<UpgradeOverdueDetected> published = ArgumentCaptor.forClass(UpgradeOverdueDetected.class);
        verify(eventPublisher, times(3)).publish(published.capture());
        assertThat(published.getAllValues())
                .extracting(UpgradeOverdueDetected::upgradeId)
                .containsExactly(first.getId(), second.getId(), last.getId());
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
    void GivenTwoOverdueUpgrades_WhenTheOverdueSweepRuns_ThenItsLineCountsBothAnnouncedAndNothingElse() {
        // Two, not one, so a line reporting a constant 1 says so. The query's candidates are all overdue, so a
        // second count of them would only ever repeat this one; UpgradePersistenceIT pins the query instead.
        when(upgradeQuery.findActivePastTargetDate(today))
                .thenReturn(List.of(
                        activeUpgrade(UUID.randomUUID(), today.minusDays(1)),
                        activeUpgrade(UUID.randomUUID(), today.minusDays(2))));

        List<ILoggingEvent> logged = logsFromScheduler(scheduler::detectOverdueUpgrades);

        assertThat(logged)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("job=" + JOB, "announced=2")
                .doesNotContain("scanned");
    }

    @Test
    void GivenASweep_WhenItFinishes_ThenTheRunIsTimedAndCountedAsOkUnderItsJobName() {
        when(upgradeQuery.findActivePastTargetDate(today)).thenReturn(List.of());

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
        when(upgradeQuery.findActivePastTargetDate(today)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(scheduler::detectOverdueUpgrades).isInstanceOf(IllegalStateException.class);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("ok")).isZero();
    }

    private double runs(String outcome) {
        return meterRegistry.find("scheduled.job.runs").tag("job", JOB).tag("outcome", outcome).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    /** Collects what the sweep logs while {@code job} runs: {@link UpgradeOverdueService} writes its line. */
    private static List<ILoggingEvent> logsFromScheduler(Runnable job) {
        Logger logger = (Logger) LoggerFactory.getLogger(UpgradeOverdueService.class);
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
