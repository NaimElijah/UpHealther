package com.healthupgrades.upgrade.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port through which this context obtains the tracking setup shown alongside an upgrade.
 *
 * <p>The upgrade response carries its tracking configuration. Reaching into {@code tracking} for it would
 * make a cycle between two bounded contexts, because {@code tracking} already reaches back for ownership
 * checks. So the direction is inverted: this context declares the contract, and whichever context can
 * satisfy it implements it. The dependency points only one way, from the supplier to this port.
 *
 * <p>Implementations must tolerate ids with no configuration by omitting them from the result rather
 * than returning nulls.
 */
public interface UpgradeTrackingSummaryPort {

    /**
     * Summaries for the given upgrades, keyed by upgrade id.
     *
     * <p>Batched rather than per-upgrade so mapping a list stays a single lookup instead of N.
     *
     * @param upgradeIds the upgrades to describe; an empty collection yields an empty map
     * @return a map containing an entry only for upgrades that have a tracking configuration
     */
    Map<UUID, UpgradeTrackingSummary> findByUpgradeIds(Collection<UUID> upgradeIds);
}
