package com.healthupgrades.common.domain.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProgressEntryRecorded(UUID progressId, UUID upgradeId, UUID userId, LocalDate date, LocalDateTime occurredAt) implements DomainEvent {}
