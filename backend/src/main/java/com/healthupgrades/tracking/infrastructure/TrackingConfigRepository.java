package com.healthupgrades.tracking.infrastructure;

import com.healthupgrades.tracking.domain.TrackingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TrackingConfigRepository extends JpaRepository<TrackingConfig, UUID> {
    Optional<TrackingConfig> findByUpgradeId(UUID upgradeId);
}
