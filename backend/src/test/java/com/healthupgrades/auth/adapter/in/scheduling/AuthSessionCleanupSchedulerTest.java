package com.healthupgrades.auth.adapter.in.scheduling;

import com.healthupgrades.auth.application.AuthSessionService;
import com.healthupgrades.common.observability.JobMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers NFR-25 for the session sweep: every run records how long it took and whether it finished.
 *
 * <p>This job is the one whose failure is invisible by design. An expired session is refused whether or
 * not the sweep has run, so a sweep that has been failing for a month changes nothing a user can see -
 * the table just grows. The run counter is the only place that shows it.
 *
 * <p>The job name is asserted as a literal because it is a metric tag, and a renamed tag silently breaks
 * every dashboard and alert that selects on it.
 */
@ExtendWith(MockitoExtension.class)
class AuthSessionCleanupSchedulerTest {

    private static final String JOB = "auth.session-cleanup";

    @Mock AuthSessionService sessionService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private AuthSessionCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        // A real JobMetrics, not a mock: it is what runs the job body, so a stub would run nothing.
        scheduler = new AuthSessionCleanupScheduler(sessionService, new JobMetrics(meterRegistry));
    }

    @Test
    void GivenSessionsNothingCanUse_WhenTheSweepRuns_ThenTheyAreDeletedAndTheRunIsTimedAndCountedAsOk() {
        when(sessionService.deleteUnusableSessions()).thenReturn(3);

        scheduler.deleteUnusableSessions();

        verify(sessionService).deleteUnusableSessions();
        assertThat(runs("ok")).isEqualTo(1);
        assertThat(meterRegistry.get("scheduled.job.duration").tag("job", JOB).timer().count()).isEqualTo(1);
    }

    @Test
    void GivenNothingToDelete_WhenTheSweepRuns_ThenTheRunIsStillCountedAsOk() {
        // "Ran and found nothing" must be distinguishable from "did not run", which is the whole of
        // NFR-25. Staying silent in the log is fine; staying silent in the counter is not.
        when(sessionService.deleteUnusableSessions()).thenReturn(0);

        scheduler.deleteUnusableSessions();

        assertThat(runs("ok")).isEqualTo(1);
    }

    @Test
    void GivenTheDeleteFails_WhenTheSweepRuns_ThenTheFaultEscapesAndTheRunIsCountedAsFailed() {
        when(sessionService.deleteUnusableSessions()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(scheduler::deleteUnusableSessions).isInstanceOf(IllegalStateException.class);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("ok")).isZero();
    }

    private double runs(String outcome) {
        return meterRegistry.find("scheduled.job.runs")
                .tag("job", JOB).tag("outcome", outcome).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }
}
