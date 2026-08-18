package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a running upgrade is finished successfully — a terminal transition, since a COMPLETED
 * upgrade can no longer be reactivated, paused or rescheduled.
 *
 * @param upgradeId  the upgrade that was completed
 * @param userId     its owner
 * @param occurredAt when the transition happened
 */
public record HealthUpgradeCompleted(UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
