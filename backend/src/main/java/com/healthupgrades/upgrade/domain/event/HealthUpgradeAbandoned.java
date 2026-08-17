package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

public record HealthUpgradeAbandoned(UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
