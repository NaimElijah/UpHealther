package com.healthupgrades.notification.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.healthupgrades.common.observability.JobMetrics;
import com.healthupgrades.notification.application.NotificationRetentionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers the nightly retention job's trigger: that it runs the deletion, and how the run is counted. */
@ExtendWith(MockitoExtension.class)
class NotificationRetentionSchedulerTest {

    private static final String JOB = "notification.retention";

    @Mock
    NotificationRetentionService retention;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private NotificationRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        // A real JobMetrics, not a mock: it is what runs the job body, so a stub would run nothing.
        scheduler = new NotificationRetentionScheduler(retention, new JobMetrics(meterRegistry));
    }

    @Test
    void GivenExpiredNotifications_WhenTheJobRuns_ThenTheyAreDeletedAndTheRunIsTimedAndCountedAsOk() {
        when(retention.deleteExpired()).thenReturn(3);

        scheduler.deleteExpiredNotifications();

        verify(retention).deleteExpired();
        assertThat(runs("ok")).isEqualTo(1);
        assertThat(meterRegistry
                        .get("scheduled.job.duration")
                        .tag("job", JOB)
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void GivenNothingToDelete_WhenTheJobRuns_ThenTheRunIsStillCountedAsOk() {
        // "Ran and found nothing" must be distinguishable from "did not run" (NFR-25).
        when(retention.deleteExpired()).thenReturn(0);

        scheduler.deleteExpiredNotifications();

        assertThat(runs("ok")).isEqualTo(1);
    }

    @Test
    void GivenTheDeleteFails_WhenTheJobRuns_ThenTheFaultEscapesAndTheRunIsCountedAsFailed() {
        when(retention.deleteExpired()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(scheduler::deleteExpiredNotifications).isInstanceOf(IllegalStateException.class);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("ok")).isZero();
    }

    private double runs(String outcome) {
        return meterRegistry.find("scheduled.job.runs").tag("job", JOB).tag("outcome", outcome).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }
}
