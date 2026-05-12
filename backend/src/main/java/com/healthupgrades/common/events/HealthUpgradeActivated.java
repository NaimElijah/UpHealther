package com.healthupgrades.common.events;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record HealthUpgradeActivated(UUID upgradeId, UUID userId, LocalDate startDate, LocalDateTime occurredAt) implements DomainEvent {}
