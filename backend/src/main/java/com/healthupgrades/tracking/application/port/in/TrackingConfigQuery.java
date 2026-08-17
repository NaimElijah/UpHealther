package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.TrackingConfig; // returned domain aggregate

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound port exposing tracking-config reads as DOMAIN objects, so other contexts (the upgrade web
 * adapter composing {@code UpgradeDto}) can obtain configs without touching tracking's persistence.
 */
public interface TrackingConfigQuery {

    /**
     * Configs for the given upgrades in one query.
     *
     * <p>Batched only, deliberately: every consumer resolves configs for a set of upgrades, and offering
     * a single-id variant alongside invites the N+1 this exists to avoid. One id is a set of one.
     */
    List<TrackingConfig> findByUpgradeIds(Collection<UUID> upgradeIds);
}
