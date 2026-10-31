package com.healthupgrades.upgrade.application.port.in;

import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound port for the scheduled sweeps that read upgrades across every user: the overdue scan and the
 * notification sweeps.
 *
 * <p><strong>Every user's rows, not one user's.</strong> Nothing here is scoped by an owner, so it exists
 * only for scheduled work, and only the jobs' application services may depend on it, which
 * {@code HexagonalArchitectureTest} enforces. A request-driven read takes the owner's id and lives on
 * the context's user-facing query port instead (BR-15).
 */
public interface UpgradeSweepQuery {

    /**
     * Every ACTIVE upgrade whose target date is before a given day, whoever owns it: the overdue sweep's
     * candidates, so it never loads the active upgrades that are on time.
     *
     * @param today the day a target date is judged against; an upgrade due on it is not yet late
     * @return the matching upgrades of every user
     */
    List<HealthUpgrade> findActivePastTargetDate(LocalDate today);

    /**
     * How many upgrades in a status each user has, whoever they are, without loading the upgrades.
     *
     * @param status the status to count
     * @return each user with at least one upgrade in it, and how many; a user with none is absent
     */
    Map<UUID, Long> countByOwner(UpgradeStatus status);

    /**
     * The upgrades with the given ids, whoever owns them.
     *
     * @param ids the upgrades to load, in one query
     * @return those of them that exist; an unknown id is left out
     */
    List<HealthUpgrade> findAllById(Iterable<UUID> ids);
}
