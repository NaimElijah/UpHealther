package com.healthupgrades.common.events;

import java.time.LocalDateTime;
import java.util.UUID;

public record HealthUpgradeCreated(UUID upgradeId, UUID userId, String title, LocalDateTime occurredAt) implements DomainEvent {}
