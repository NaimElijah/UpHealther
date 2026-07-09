package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.TrackingConfig; // returned domain aggregate

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound port exposing tracking-config reads as DOMAIN objects, so other contexts (the upgrade web
 * adapter composing {@code UpgradeDto}) can obtain configs without touching tracking's persistence.
 */
public interface TrackingConfigQuery {

    /** The tracking config for a single upgrade, if configured. */
    Optional<TrackingConfig> findByUpgradeId(UUID upgradeId);

    /** Configs for many upgrades in one query (avoids N+1 when mapping upgrade lists). */
    List<TrackingConfig> findByUpgradeIds(Collection<UUID> upgradeIds);
}
