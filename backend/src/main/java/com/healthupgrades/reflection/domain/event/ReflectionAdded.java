package com.healthupgrades.reflection.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReflectionAdded(UUID reflectionId, UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
