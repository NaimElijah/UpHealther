package com.healthupgrades.upgrade.application;

import com.healthupgrades.upgrade.application.port.in.UpgradeSweepQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The scheduled sweeps' reads of every user's upgrades: {@link UpgradeSweepQuery} over the repository.
 *
 * <p>A class of its own rather than more methods on the context's service, because a controller holds that
 * service: a sweep method on it would be one call away from a request handler, whatever the port's own
 * dependents are. Only the jobs' application services hold this port, which
 * {@code HexagonalArchitectureTest} enforces.
 */
@Service
@RequiredArgsConstructor
class UpgradeSweeps implements UpgradeSweepQuery {

    private final UpgradeRepositoryPort repository; // outbound persistence port

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findActivePastTargetDate(LocalDate today) {
        return repository.findActivePastTargetDate(today);
    }

    /** {@inheritDoc} */
    @Override
    public Map<UUID, Long> countByOwner(UpgradeStatus status) {
        return repository.countByOwner(status);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findAllById(Iterable<UUID> ids) {
        return repository.findAllById(ids);
    }
}
