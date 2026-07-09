package com.healthupgrades.tracking.domain.port.out;

import com.healthupgrades.tracking.domain.TrackingConfig; // the aggregate this port persists

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and querying {@link TrackingConfig} rows (per-upgrade tracking setup).
 */
public interface TrackingConfigRepositoryPort {

    /** Persists a new or updated tracking config and returns the managed instance. */
    TrackingConfig save(TrackingConfig config);

    /** The tracking config for a single upgrade, if configured. */
    Optional<TrackingConfig> findByUpgradeId(UUID upgradeId);

    /** Configs for many upgrades in one query (avoids N+1 on list/dashboard views). */
    List<TrackingConfig> findByUpgradeIdIn(Collection<UUID> upgradeIds);
}
