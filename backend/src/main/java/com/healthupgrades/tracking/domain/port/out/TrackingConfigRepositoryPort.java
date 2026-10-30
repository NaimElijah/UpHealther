package com.healthupgrades.tracking.domain.port.out;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.tracking.domain.model.TrackingConfig; // the aggregate this port persists
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and querying {@link TrackingConfig} rows (per-upgrade tracking setup).
 */
public interface TrackingConfigRepositoryPort {

    /**
     * Persists a new or updated tracking config and returns the managed instance, writing it before
     * returning so a refusal is raised here rather than at commit.
     *
     * @throws RetryableConflictException if the config is new and another config for the same upgrade was
     *                                    stored since the caller looked for one
     * @throws ResourceNotFoundException  if the config's upgrade no longer exists, because it was deleted
     *                                    after the caller confirmed it
     */
    TrackingConfig save(TrackingConfig config);

    /** The tracking config for a single upgrade, if configured. */
    Optional<TrackingConfig> findByUpgradeId(UUID upgradeId);

    /** Configs for many upgrades in one query (avoids N+1 on list/dashboard views). */
    List<TrackingConfig> findByUpgradeIdIn(Collection<UUID> upgradeIds);
}
