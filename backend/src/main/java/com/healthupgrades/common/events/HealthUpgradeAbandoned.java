package com.healthupgrades.common.events;

import java.time.LocalDateTime;
import java.util.UUID;

public record HealthUpgradeAbandoned(UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
