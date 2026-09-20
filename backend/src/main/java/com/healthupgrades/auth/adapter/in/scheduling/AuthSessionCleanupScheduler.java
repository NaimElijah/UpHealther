package com.healthupgrades.auth.adapter.in.scheduling;

import com.healthupgrades.auth.application.AuthSessionService;
import com.healthupgrades.common.observability.JobMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

/**
 * Driving adapter that periodically deletes sessions nothing can use again.
 *
 * <p>Without it the table only grows: every sign-in adds a row, and a row stays behind when a session
 * is revoked, idles out, or reaches its absolute cap. Nothing reads those rows, and each one holds a
 * token digest, so keeping them is storage spent on a liability.
 *
 * <p>Correctness does not depend on this running. An expired session is already refused by
 * {@code AuthSession.isActive}, which reads the row's own timestamps rather than trusting that a sweep
 * has been through. If the job stops, the table grows and nothing else changes — which is exactly why
 * it is worth measuring, and why the run is timed and counted like the others.
 *
 * <p>The cron expression is configured under {@code app.auth.schedules.session-cleanup}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthSessionCleanupScheduler {

    /** Stable across releases: it is a metric tag and a log field, so it is a contract, not a label. */
    private static final String JOB = "auth.session-cleanup";

    private final AuthSessionService sessionService;
    private final JobMetrics jobMetrics; // times the run and counts how it ended

    /**
     * Removes revoked and expired sessions.
     *
     * <p>Counts only, and silent when there was nothing to remove: a nightly line saying zero buries the
     * nights that removed thousands.
     */
    @Scheduled(cron = "${app.auth.schedules.session-cleanup}")
    public void deleteUnusableSessions() {
        jobMetrics.timed(JOB, () -> {
            int removed = sessionService.deleteUnusableSessions();
            if (removed > 0) {
                log.info("Removed sessions that can no longer be used {}", keyValue("removed", removed));
            }
        });
    }
}
