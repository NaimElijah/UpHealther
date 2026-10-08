package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised by the scheduled overdue sweep when an ACTIVE upgrade is found past its target end date.
 *
 * <p>The only event here that no user action produces, and the reason the sweep exists: nothing else
 * notices that a date has passed. It fires once per sweep for as long as the upgrade stays overdue —
 * the notification listener is what decides to notify once per target date (BR-11), which is why the
 * date that was missed travels with the event.
 *
 * @param upgradeId     the overdue upgrade
 * @param userId        its owner
 * @param targetEndDate the target date the upgrade has missed
 * @param occurredAt    when the sweep observed it
 */
public record UpgradeOverdueDetected(UUID upgradeId, UUID userId, LocalDate targetEndDate, LocalDateTime occurredAt)
        implements DomainEvent {}
