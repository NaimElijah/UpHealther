package com.healthupgrades.tracking.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a newly recorded entry carries the user's streak to or past a milestone.
 *
 * <p>Only milestone lengths raise this — every consecutive day would otherwise produce a notification a
 * day, which is how a streak stops being an achievement. The milestones, and when an entry has reached
 * one, are decided by {@code StreakCalculator.milestoneReachedBy}.
 *
 * @param upgradeId  the upgrade being kept up
 * @param userId     the owner
 * @param streakDays the milestone reached, a multiple of seven; the streak itself can be longer when a
 *                   backfilled day joined two runs
 * @param occurredAt when the milestone was reached
 */
public record StreakAchieved(UUID upgradeId, UUID userId, int streakDays, LocalDateTime occurredAt) implements DomainEvent {}
