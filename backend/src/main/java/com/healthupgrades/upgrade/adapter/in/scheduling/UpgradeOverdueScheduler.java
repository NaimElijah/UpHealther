package com.healthupgrades.upgrade.adapter.in.scheduling;

import static com.healthupgrades.upgrade.application.UpgradeOverdueService.JOB;

import com.healthupgrades.common.observability.JobMetrics;
import com.healthupgrades.common.time.ServerZone;
import com.healthupgrades.upgrade.application.UpgradeOverdueService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter that runs the overdue sweep, {@link UpgradeOverdueService#announceOverdue}, nightly.
 *
 * <p>A trigger and nothing more: it reads the clock in the server's zone, and times the run and counts
 * how it ended under the job's name (NFR-25). The cron expression is configured under
 * {@code app.upgrades.schedules.overdue}.
 */
@Component
@RequiredArgsConstructor
public class UpgradeOverdueScheduler {

    private final UpgradeOverdueService overdue; // own application service: the sweep
    private final JobMetrics jobMetrics; // times the run and counts how it ended
    private final Clock clock; // injectable clock keeps "today" deterministic

    /** Announces every active upgrade now past its target date. */
    @Scheduled(cron = "${app.upgrades.schedules.overdue}", zone = ServerZone.ID)
    public void detectOverdueUpgrades() {
        jobMetrics.timed(JOB, () -> overdue.announceOverdue(LocalDate.now(clock), LocalDateTime.now(clock)));
    }
}
