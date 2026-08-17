package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record HealthUpgradePlanned(UUID upgradeId, UUID userId, LocalDate plannedStartDate, LocalDateTime occurredAt) implements DomainEvent {}
