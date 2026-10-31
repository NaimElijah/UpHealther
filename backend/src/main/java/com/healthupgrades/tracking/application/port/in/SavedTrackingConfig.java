package com.healthupgrades.tracking.application.port.in;

import com.healthupgrades.tracking.domain.model.TrackingConfig;

/**
 * A stored tracking configuration, and whether storing it created the upgrade's configuration or
 * replaced the one it had.
 *
 * <p>A result record rather than the bare aggregate, because the web adapter answers the two
 * differently, 201 and 200 (RFC 9110 section 9.3.4), and only the use case knows which happened.
 *
 * @param config  the configuration as stored
 * @param created true when the upgrade had no configuration before this save
 */
public record SavedTrackingConfig(TrackingConfig config, boolean created) {}
