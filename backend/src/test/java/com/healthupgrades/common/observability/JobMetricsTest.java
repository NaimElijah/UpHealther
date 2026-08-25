package com.healthupgrades.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the two meters that make scheduled work visible.
 *
 * <p>Scheduled work is the part of this application nobody is watching: a request that fails answers
 * somebody with a 500, while a nightly sweep that has been throwing for a week looks exactly like a
 * nightly sweep with nothing to do. The property that matters most here is that a failing run is still
 * <em>counted</em> and still <em>thrown</em> — counted so it can be alerted on, thrown so Spring's own
 * error handler still logs the stack trace rather than this class swallowing it into a summary.
 */
class JobMetricsTest {

    private static final String JOB = "upgrade.overdue-scan";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final JobMetrics jobMetrics = new JobMetrics(meterRegistry);

    @Test
    void GivenAJobThatFinishes_WhenItIsTimed_ThenItRunsAndIsCountedAsOk() {
        StringBuilder ran = new StringBuilder();

        jobMetrics.timed(JOB, () -> ran.append("swept"));

        assertThat(ran).hasToString("swept");
        assertThat(runs("ok")).isEqualTo(1);
        assertThat(meterRegistry.get("scheduled.job.duration").tag("job", JOB).timer().count()).isEqualTo(1);
    }

    @Test
    void GivenAJobThatThrows_WhenItIsTimed_ThenItIsCountedAsFailedAndTheFaultStillEscapes() {
        assertThatThrownBy(() -> jobMetrics.timed(JOB, () -> {
            throw new IllegalStateException("the query blew up");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("ok")).isZero();
    }

    @Test
    void GivenAJobThatThrows_WhenItIsTimed_ThenItsDurationIsStillRecorded() {
        // A run that failed after thirty seconds is exactly the run worth knowing the duration of.
        assertThatThrownBy(() -> jobMetrics.timed(JOB, () -> {
            throw new IllegalStateException("the query blew up");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.get("scheduled.job.duration").tag("job", JOB).timer().count()).isEqualTo(1);
    }

    @Test
    void GivenAJobThatDiesOnAnError_WhenItIsTimed_ThenItIsCountedAsFailedRatherThanAsACleanRun() {
        // The failure mode that makes this class worth having and is easiest to get wrong: an Error -
        // NoClassDefFoundError from a missing transitive dependency, OutOfMemoryError, a static
        // initialiser blowing up - is not a RuntimeException. Deciding the outcome in a catch means
        // an Error walks past the assignment and gets counted as ok, holding the failure counter at
        // zero while the sweep has not worked in a week. Nobody is answered with a 500 by a scheduled
        // job, so that counter is the only signal there is.
        assertThatThrownBy(() -> jobMetrics.timed(JOB, () -> {
            throw new NoClassDefFoundError("com/example/Gone");
        })).isInstanceOf(NoClassDefFoundError.class);

        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("ok")).isZero();
    }

    @Test
    void GivenTwoJobs_WhenBothAreTimed_ThenTheirCountsAreSeparate() {
        jobMetrics.timed(JOB, () -> { });
        jobMetrics.timed("notification.reminder-dispatch", () -> { });

        assertThat(runs("ok")).isEqualTo(1);
        assertThat(meterRegistry.get("scheduled.job.runs")
                .tag("job", "notification.reminder-dispatch").tag("outcome", "ok").counter().count()).isEqualTo(1);
    }

    @Test
    void GivenAnyRun_WhenItIsCounted_ThenTheOnlyTagsAreTheJobAndTheOutcome() {
        jobMetrics.timed(JOB, () -> { });

        assertThat(meterRegistry.get("scheduled.job.runs").counter().getId().getTags())
                .extracting("key")
                .containsExactlyInAnyOrder("job", "outcome");
    }

    private double runs(String outcome) {
        return meterRegistry.find("scheduled.job.runs")
                .tag("job", JOB).tag("outcome", outcome).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }
}
