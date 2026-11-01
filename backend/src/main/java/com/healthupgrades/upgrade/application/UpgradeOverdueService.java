package com.healthupgrades.upgrade.application;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.upgrade.application.port.in.UpgradeSweepQuery;
import com.healthupgrades.upgrade.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Looks for active upgrades which have passed their target date, and announces each as a domain event.
 *
 * <p>Overdue-ness is a fact about an upgrade, so this context detects and publishes it; deciding what to
 * do about it — notifying the owner, say — belongs to whoever listens. The use case lives here rather
 * than in the scheduler that runs it daily, so a second trigger has one to call; the trigger supplies
 * the day and the instant, because reading the clock is part of being triggered.
 *
 * <p>Beside {@link UpgradeService} rather than in it, and deliberately not {@code @Transactional}: the
 * sweep runs outside any transaction, so each event is published, and its listeners run, at once rather
 * than on a commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UpgradeOverdueService {

    /** The sweep's name. Stable across releases: it is a metric tag and a log field, so it is a contract. */
    public static final String JOB = "upgrade.overdue-scan";

    private final UpgradeSweepQuery upgradeQuery; // own context's read view across every user
    private final DomainEventPublisher eventPublisher;

    /**
     * Publishes {@link UpgradeOverdueDetected} for every active upgrade past its target date on a given day.
     *
     * <p>The INFO line is what makes a daily sweep visible at all: it says the run happened, and counts the
     * upgrades it announced. Counts only — an upgrade's title is the user's own words about their health.
     * The query returns only active upgrades past their target date, so a count of what it returned would
     * repeat {@code announced}, and the line cannot tell a query that silently stopped matching from a day
     * with nothing overdue: {@code UpgradePersistenceIT} pins the query instead.
     *
     * @param today      the day an upgrade's target date is judged against
     * @param detectedAt the instant every event of this sweep carries
     * @throws RuntimeException whatever a listener of the event throws, which ends the sweep there
     */
    public void announceOverdue(LocalDate today, LocalDateTime detectedAt) {
        int announced = 0;
        // The query narrows to the candidates; the entity still decides, so the rule is stated once.
        for (HealthUpgrade upgrade : upgradeQuery.findActivePastTargetDate(today)) {
            if (upgrade.isOverdue(today)) {
                announced++;
                eventPublisher.publish(new UpgradeOverdueDetected(
                        upgrade.getId(), upgrade.getUserId(), upgrade.getTargetEndDate(), detectedAt));
            }
        }

        log.info("{} {}", keyValue("job", JOB), keyValue("announced", announced));
    }
}
