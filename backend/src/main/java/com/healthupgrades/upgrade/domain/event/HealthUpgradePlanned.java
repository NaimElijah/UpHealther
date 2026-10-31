package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when an upgrade becomes PLANNED: an IDEA committed to a start date, or an ABANDONED upgrade
 * revived by a reschedule.
 *
 * @param upgradeId        the upgrade that was planned
 * @param userId           its owner
 * @param plannedStartDate the date the user intends to start
 * @param occurredAt       when the transition happened
 */
public record HealthUpgradePlanned(UUID upgradeId, UUID userId, LocalDate plannedStartDate, LocalDateTime occurredAt)
        implements DomainEvent {}
