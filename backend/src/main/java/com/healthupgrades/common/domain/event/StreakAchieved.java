package com.healthupgrades.common.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record StreakAchieved(UUID upgradeId, UUID userId, int streakDays, LocalDateTime occurredAt) implements DomainEvent {}
