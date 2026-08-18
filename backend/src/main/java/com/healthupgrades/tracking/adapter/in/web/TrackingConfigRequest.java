package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for creating or replacing an upgrade's tracking configuration.
 *
 * <p>A full replacement: an omitted optional field is stored as null rather than left at its previous
 * value.
 *
 * @param trackingType       how progress is measured; required, and it decides which of the rest apply
 * @param frequency          how often the upgrade is expected to be acted on, optional
 * @param targetNumericValue NUMERIC tracking: the value an entry must reach, optional
 * @param targetUnit         NUMERIC tracking: the unit the target is in; an entry logged in a different
 *                           unit is never scored as a success, optional
 * @param requiredDaily      whether this is a must-do each day, optional
 */
public record TrackingConfigRequest(
        @NotNull TrackingType trackingType,
        Frequency frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
