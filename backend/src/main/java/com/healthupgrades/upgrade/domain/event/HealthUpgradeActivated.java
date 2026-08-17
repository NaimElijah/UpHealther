package com.healthupgrades.upgrade.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record HealthUpgradeActivated(UUID upgradeId, UUID userId, LocalDate startDate, LocalDateTime occurredAt) implements DomainEvent {}
