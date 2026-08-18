package com.healthupgrades.tracking.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a newly recorded entry lands the user on a streak worth remarking on.
 *
 * <p>Only milestone lengths raise this — every consecutive day would otherwise produce a notification a
 * day, which is how a streak stops being an achievement. The milestones live in the tracking service.
 *
 * @param upgradeId  the upgrade being kept up
 * @param userId     the owner
 * @param streakDays the streak length reached
 * @param occurredAt when the milestone was reached
 */
public record StreakAchieved(UUID upgradeId, UUID userId, int streakDays, LocalDateTime occurredAt) implements DomainEvent {}
