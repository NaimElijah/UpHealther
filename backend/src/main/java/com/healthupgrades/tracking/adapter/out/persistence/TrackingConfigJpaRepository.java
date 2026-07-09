package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.tracking.domain.TrackingConfig; // managed entity
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository backing {@link TrackingConfigRepositoryAdapter}; package-private internal detail.
 */
interface TrackingConfigJpaRepository extends JpaRepository<TrackingConfig, UUID> {
    Optional<TrackingConfig> findByUpgradeId(UUID upgradeId); // derived query: config for one upgrade
    List<TrackingConfig> findByUpgradeIdIn(Collection<UUID> upgradeIds); // derived query: batch load
}
