package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.tracking.domain.TrackingConfig; // domain aggregate
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link TrackingConfigRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class TrackingConfigRepositoryAdapter implements TrackingConfigRepositoryPort {

    private final TrackingConfigJpaRepository jpa; // Spring Data proxy

    @Override
    public TrackingConfig save(TrackingConfig config) {
        return jpa.save(config); // delegate persist
    }

    @Override
    public Optional<TrackingConfig> findByUpgradeId(UUID upgradeId) {
        return jpa.findByUpgradeId(upgradeId); // delegate
    }

    @Override
    public List<TrackingConfig> findByUpgradeIdIn(Collection<UUID> upgradeIds) {
        return jpa.findByUpgradeIdIn(upgradeIds); // delegate batch load
    }
}
