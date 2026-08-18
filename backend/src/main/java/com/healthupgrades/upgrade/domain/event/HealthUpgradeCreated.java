package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a user records a new upgrade, which always enters the lifecycle as an IDEA.
 *
 * @param upgradeId  the new upgrade
 * @param userId     its owner
 * @param title      the upgrade's title, carried so a consumer can compose a message without a lookup
 * @param occurredAt when the upgrade was created
 */
public record HealthUpgradeCreated(UUID upgradeId, UUID userId, String title, LocalDateTime occurredAt) implements DomainEvent {}
