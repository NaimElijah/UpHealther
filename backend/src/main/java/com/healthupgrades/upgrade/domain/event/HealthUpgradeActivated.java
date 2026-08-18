package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when an upgrade starts running — from PLANNED, or resumed from PAUSED.
 *
 * <p>An activated upgrade occupies a HARD slot if its difficulty is HARD; see
 * {@code UpgradeSchedulingService}.
 *
 * @param upgradeId  the upgrade that became ACTIVE
 * @param userId     its owner
 * @param startDate  the date it actually started
 * @param occurredAt when the transition happened
 */
public record HealthUpgradeActivated(UUID upgradeId, UUID userId, LocalDate startDate, LocalDateTime occurredAt) implements DomainEvent {}
