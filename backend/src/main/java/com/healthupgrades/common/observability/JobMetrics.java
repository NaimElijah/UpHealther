package com.healthupgrades.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Times a scheduled run and counts how it ended.
 *
 * <p>Scheduled work is the part of this application nobody is watching. A request that fails answers
 * somebody with a 500; a nightly sweep that has been throwing for a week looks exactly like a nightly
 * sweep with nothing to do. These two meters are the difference, and they are the reason the jobs get
 * instrumented at all: {@code scheduled.job.runs} split by outcome answers "is it still running, and is
 * it still working", and {@code scheduled.job.duration} answers "is it about to stop finishing before
 * the next one starts".
 *
 * <p><b>The failure is rethrown, not handled.</b> Spring's scheduler already logs a task that threw, at
 * ERROR, through its default error handler — catching it here to log it again would produce two entries
 * for one fault and hide the stack trace behind a summary. This only counts it on the way past.
 *
 * <p>The job name is a tag, so it is a closed set by construction: the constants live beside the
 * {@code @Scheduled} methods that use them and there are three of them. Nothing per-user or per-record
 * may join it — see {@code docs/ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md}.
 */
@Component
@RequiredArgsConstructor
public class JobMetrics {

    private static final String RUNS = "scheduled.job.runs";
    private static final String DURATION = "scheduled.job.duration";
    private static final String OK = "ok";
    private static final String FAILED = "failed";

    private final MeterRegistry meterRegistry;

    /**
     * Runs a scheduled job, recording how long it took and whether it finished.
     *
     * @param job  the job's stable name, used as a metric tag
     * @param work the job body
     * @throws RuntimeException whatever the job threw, untouched, so the scheduler still reports it
     */
    public void timed(String job, Runnable work) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = OK;
        try {
            work.run();
        } catch (RuntimeException thrown) {
            outcome = FAILED;
            throw thrown;
        } finally {
            sample.stop(Timer.builder(DURATION)
                    .description("How long a scheduled run took")
                    .tag("job", job)
                    .register(meterRegistry));
            Counter.builder(RUNS)
                    .description("Scheduled runs, by job and how they ended")
                    .tag("job", job)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
