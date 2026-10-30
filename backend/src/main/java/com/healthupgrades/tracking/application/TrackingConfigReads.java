package com.healthupgrades.tracking.application;

import com.healthupgrades.tracking.application.port.in.TrackingConfigQuery;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The tracking configurations of a set of upgrades, whoever owns them: {@link TrackingConfigQuery} over the
 * repository.
 *
 * <p>Package-private, and a class of its own rather than a method on {@link TrackingService}, because a
 * controller holds that service: the unscoped read would be one call away from a request handler, past
 * every rule on the port (S5.B6). {@code HexagonalArchitectureTest} keeps it package-private.
 */
@Service
@RequiredArgsConstructor
class TrackingConfigReads implements TrackingConfigQuery {

    private final TrackingConfigRepositoryPort configRepository; // outbound: tracking configs

    /** {@inheritDoc} */
    @Override
    public List<TrackingConfig> findByUpgradeIds(Collection<UUID> upgradeIds) {
        return configRepository.findByUpgradeIdIn(upgradeIds);
    }
}
