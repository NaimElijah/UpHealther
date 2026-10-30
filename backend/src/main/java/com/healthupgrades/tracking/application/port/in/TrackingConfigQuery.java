package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.TrackingConfig; // returned domain aggregate
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Inbound port exposing tracking-config reads as DOMAIN objects, for this context's
 * {@code UpgradeTrackingSummaryAdapter}, which answers the upgrade context's
 * {@code UpgradeTrackingSummaryPort} when it composes an {@code UpgradeDto}.
 *
 * <p><strong>Not scoped by owner</strong>, unlike the other user-facing reads (BR-15): it returns the
 * configuration of every id it is given, whoever owns the upgrade. {@code tracking_configs} has no owner
 * column to filter on. It is safe only because its one caller maps upgrades that were already loaded
 * through user-scoped reads, so no id a request supplies reaches it. {@code HexagonalArchitectureTest} pins
 * each hop with a rule of its own: this port serves only {@code UpgradeTrackingSummaryAdapter} and its
 * implementer; the {@code UpgradeTrackingSummaryPort} the adapter answers serves only
 * {@code UpgradeWebMapper}; and that mapper serves only {@code UpgradeController} and
 * {@code DashboardWebMapper}, which in turn serves only {@code DashboardController}. Another caller of any
 * of them, or a request-supplied id reaching one, would make it unsafe; scope it first.
 */
public interface TrackingConfigQuery {

    /**
     * Configs for the given upgrades in one query, whoever owns them.
     *
     * <p>Batched only, deliberately: every consumer resolves configs for a set of upgrades, and offering
     * a single-id variant alongside invites the N+1 this exists to avoid. One id is a set of one.
     *
     * @param upgradeIds the upgrades whose configs are read; each must come from a user-scoped read
     * @return the configs of those upgrades that have one
     */
    List<TrackingConfig> findByUpgradeIds(Collection<UUID> upgradeIds);
}
