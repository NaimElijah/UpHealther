package com.healthupgrades.upgrade.adapter.in.web;

import java.util.UUID;

/**
 * The tracking configuration as embedded in an upgrade response.
 *
 * <p>Field names and order match the standalone tracking-config response exactly, so the JSON on the
 * wire is unchanged. It is declared here rather than reused from the tracking context because a
 * response record is part of this endpoint's contract: borrowing another context's DTO made a rename
 * there a breaking change here.
 */
public record UpgradeTrackingConfigDto(
        UUID id,
        UUID upgradeId,
        String trackingType,
        String frequency,
        Double targetNumericValue,
        String targetUnit,
        Boolean requiredDaily
) {}
