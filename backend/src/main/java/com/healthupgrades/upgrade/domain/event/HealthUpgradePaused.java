package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a running upgrade is paused, releasing any HARD slot it held.
 *
 * @param upgradeId  the upgrade that was paused
 * @param userId     its owner
 * @param occurredAt when the transition happened
 */
public record HealthUpgradePaused(UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
