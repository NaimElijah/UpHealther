package com.healthupgrades.tracking.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProgressEntryRecorded(UUID progressId, UUID upgradeId, UUID userId, LocalDate date, LocalDateTime occurredAt) implements DomainEvent {}
