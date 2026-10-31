package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.upgrade.application.port.in.UpgradeTrackingFrequency;
import com.healthupgrades.upgrade.application.port.in.UpgradeTrackingType;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The tracking configuration as embedded in an upgrade response.
 *
 * <p>Field names and order match the standalone tracking-config response exactly, so the JSON on the
 * wire is unchanged. It is declared here rather than reused from the tracking context because a
 * response record is part of this endpoint's contract: borrowing another context's DTO made a rename
 * there a breaking change here. The two enum fields are this context's mirrors of tracking's enums, so the
 * frontend's type carries the closed sets rather than strings (NFR-10, ADR-077).
 */
public record UpgradeTrackingConfigDto(
        UUID id,
        UUID upgradeId,
        UpgradeTrackingType trackingType,
        @Nullable UpgradeTrackingFrequency frequency,
        @Nullable Double targetNumericValue,
        @Nullable String targetUnit,
        @Nullable Boolean requiredDaily) {}
