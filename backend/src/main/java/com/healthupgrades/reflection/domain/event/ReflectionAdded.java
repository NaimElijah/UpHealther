package com.healthupgrades.reflection.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when a user writes a reflection about an upgrade.
 *
 * <p>Carries ids only, no content: a reflection is where a user writes candidly about what is not
 * working, and that text has no business travelling to consumers that only need to know one was added.
 *
 * @param reflectionId the reflection that was written
 * @param upgradeId    the upgrade it is about
 * @param userId       the author
 * @param occurredAt   when it was written
 */
public record ReflectionAdded(UUID reflectionId, UUID upgradeId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
