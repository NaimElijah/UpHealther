package com.healthupgrades.upgrade.adapter.in.scheduling;

import com.healthupgrades.common.domain.event.DomainEventPublisher;
import com.healthupgrades.common.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
public class UpgradeOverdueScheduler {

    private final UpgradeQuery upgradeQuery; // own context's read view
    private final DomainEventPublisher eventPublisher; // outbound port
    private final Clock clock; // injectable clock keeps "today" deterministic

    /** Publishes {@link UpgradeOverdueDetected} for every active upgrade now past its target date. */
    @Scheduled(cron = "${app.upgrades.schedules.overdue}")
    public void detectOverdueUpgrades() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime detectedAt = LocalDateTime.now(clock);

        for (HealthUpgrade upgrade : upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)) {
            if (upgrade.isOverdue(today)) {
                eventPublisher.publish(
                        new UpgradeOverdueDetected(upgrade.getId(), upgrade.getUserId(), detectedAt));
            }
        }
    }
}
