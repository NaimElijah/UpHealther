package com.healthupgrades.common.events;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record HealthUpgradePlanned(UUID upgradeId, UUID userId, LocalDate plannedStartDate, LocalDateTime occurredAt) implements DomainEvent {}
