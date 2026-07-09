package com.healthupgrades.common.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record UpgradeOverdueDetected(UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
