package com.healthupgrades.healtharea.application.port.in;

import com.healthupgrades.healtharea.domain.HealthArea; // returned domain aggregate

import java.util.List;
import java.util.UUID;

/**
 * Inbound port exposing a user's health areas as DOMAIN objects, so the dashboard can build its area
 * summaries without touching the health-area persistence.
 */
public interface HealthAreaQuery {

    /** All health areas owned by a user. */
    List<HealthArea> listByUser(UUID userId);
}
