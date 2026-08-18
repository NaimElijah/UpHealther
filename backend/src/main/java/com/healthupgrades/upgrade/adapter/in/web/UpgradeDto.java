package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An upgrade as returned on the wire.
 *
 * <p>Two fields are not stored on the aggregate: {@code overdue} is derived at mapping time from the
 * target date and the current status, and {@code trackingConfig} is composed in through
 * {@code UpgradeTrackingSummaryPort} — null when the upgrade has no tracking set up. {@code version}
 * is the optimistic-lock counter; a client that echoes a stale one back gets a 409.
 *
 * <p>The field order is part of the contract in practice and is pinned by
 * {@code UpgradeDtoSerializationTest}.
 *
 * @param id               the upgrade's identifier
 * @param userId           the owner
 * @param areaId           the health area it is filed under, may be null
 * @param title            short name
 * @param description      longer explanation, may be null
 * @param type             what kind of change this is
 * @param status           where it stands in its lifecycle
 * @param difficulty       how demanding it is, may be null
 * @param plannedStartDate the intended start date, may be null
 * @param actualStartDate  when it actually started, null until activated
 * @param targetEndDate    the date it should be done by, may be null
 * @param motivation       why the user wants it, may be null
 * @param successCriteria  how the user will know it worked, may be null
 * @param overdue          derived: ACTIVE and past the target end date
 * @param version          optimistic-lock version
 * @param trackingConfig   how progress is measured, null when tracking is not configured
 * @param createdAt        when the upgrade was created
 * @param updatedAt        when it was last modified
 */
public record UpgradeDto(
        UUID id,
        UUID userId,
        UUID areaId,
        String title,
        String description,
        UpgradeType type,
        UpgradeStatus status,
        Difficulty difficulty,
        LocalDate plannedStartDate,
        LocalDate actualStartDate,
        LocalDate targetEndDate,
        String motivation,
        String successCriteria,
        boolean overdue,
        Long version,
        UpgradeTrackingConfigDto trackingConfig,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
