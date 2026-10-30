package com.healthupgrades.notification.adapter.in.scheduling;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.common.observability.JobMetrics;
import com.healthupgrades.common.time.ServerZone;
import com.healthupgrades.notification.application.NotificationRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter that deletes notifications ninety days old, nightly (NFR-60, ADR-075).
 *
 * <p>A trigger and nothing more: the cutoff and the batching are {@link NotificationRetentionService}'s. The
 * run is timed and counted under its job's name (NFR-25), so a job that stopped running is told apart from
 * one that found nothing to delete.
 *
 * <p>The cron expression is configured under {@code app.notifications.schedules.retention}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRetentionScheduler {

    /** Stable across releases: it is a metric tag and a log field, so it is a contract, not a label. */
    static final String JOB = "notification.retention";

    private final NotificationRetentionService retention;
    private final JobMetrics jobMetrics; // times the run and counts how it ended

    /**
     * Deletes the notifications past their retention.
     *
     * <p>Counts only, and silent when there was nothing to remove: a nightly line saying zero buries the
     * nights that removed thousands.
     */
    @Scheduled(cron = "${app.notifications.schedules.retention}", zone = ServerZone.ID)
    public void deleteExpiredNotifications() {
        jobMetrics.timed(JOB, () -> {
            int removed = retention.deleteExpired();
            if (removed > 0) {
                log.info("Removed notifications past their retention {}", keyValue("removed", removed));
            }
        });
    }
}
