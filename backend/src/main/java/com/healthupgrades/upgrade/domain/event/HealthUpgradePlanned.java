package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when an IDEA is committed to a start date and becomes PLANNED.
 *
 * @param upgradeId        the upgrade that was planned
 * @param userId           its owner
 * @param plannedStartDate the date the user intends to start
 * @param occurredAt       when the transition happened
 */
public record HealthUpgradePlanned(UUID upgradeId, UUID userId, LocalDate plannedStartDate, LocalDateTime occurredAt) implements DomainEvent {}
