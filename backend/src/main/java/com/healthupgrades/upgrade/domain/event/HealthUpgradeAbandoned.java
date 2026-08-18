package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a user gives an upgrade up. Unlike completion this is reversible: rescheduling an
 * ABANDONED upgrade puts it back into PLANNED.
 *
 * @param upgradeId  the upgrade that was abandoned
 * @param userId     its owner
 * @param occurredAt when the transition happened
 */
public record HealthUpgradeAbandoned(UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
