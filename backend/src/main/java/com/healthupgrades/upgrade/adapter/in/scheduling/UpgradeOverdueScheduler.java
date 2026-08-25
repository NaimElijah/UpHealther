package com.healthupgrades.upgrade.adapter.in.scheduling;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.common.observability.JobMetrics;
import com.healthupgrades.upgrade.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Driving adapter that periodically looks for active upgrades which have passed their target date and
 * announces each as a domain event.
 *
 * <p>Overdue-ness is a fact about an upgrade, so this context detects and publishes it; deciding what to
 * do about it — notifying the owner, say — belongs to whoever listens. The scan runs outside any
 * transaction, so the events are published immediately rather than on commit.
 *
 * <p>The cron expression is configured under {@code app.upgrades.schedules.overdue}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpgradeOverdueScheduler {

    /** Stable across releases: it is a metric tag and a log field, so it is a contract, not a label. */
    private static final String JOB = "upgrade.overdue-scan";

    private final UpgradeQuery upgradeQuery; // own context's read view
    private final DomainEventPublisher eventPublisher; // outbound port
    private final JobMetrics jobMetrics; // times the run and counts how it ended
    private final Clock clock; // injectable clock keeps "today" deterministic

    /**
     * Publishes {@link UpgradeOverdueDetected} for every active upgrade now past its target date.
     *
     * <p>The INFO line is what makes a nightly sweep visible at all: without it, a run that scanned
     * nothing because a query silently stopped matching is indistinguishable from a run with nothing to
     * find. Counts only — an upgrade's title is the user's own words about their health.
     */
    @Scheduled(cron = "${app.upgrades.schedules.overdue}")
    public void detectOverdueUpgrades() {
        jobMetrics.timed(JOB, () -> {
            LocalDate today = LocalDate.now(clock);
            LocalDateTime detectedAt = LocalDateTime.now(clock);

            int scanned = 0;
            int announced = 0;
            for (HealthUpgrade upgrade : upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)) {
                scanned++;
                if (upgrade.isOverdue(today)) {
                    announced++;
                    eventPublisher.publish(
                            new UpgradeOverdueDetected(upgrade.getId(), upgrade.getUserId(), detectedAt));
                }
            }

            log.info("{} {} {}", keyValue("job", JOB), keyValue("scanned", scanned),
                    keyValue("announced", announced));
        });
    }
}
