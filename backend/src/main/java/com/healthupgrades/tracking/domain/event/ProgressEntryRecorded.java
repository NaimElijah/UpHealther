package com.healthupgrades.tracking.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a user logs progress against an upgrade for a given day.
 *
 * <p>Fires once per entry, and there can only ever be one entry per upgrade per date — a second is
 * rejected as a duplicate rather than replacing the first.
 *
 * @param progressId the entry that was recorded
 * @param upgradeId  the upgrade it was logged against
 * @param userId     the owner
 * @param date       the day the entry is for, which need not be the day it was logged
 * @param occurredAt when the entry was recorded
 */
public record ProgressEntryRecorded(UUID progressId, UUID upgradeId, UUID userId, LocalDate date, LocalDateTime occurredAt) implements DomainEvent {}
