package com.healthupgrades.common.domain.event;

import java.time.LocalDateTime;

/**
 * Marker for an in-process domain event.
 *
 * <p>This marker is the only event type shared between bounded contexts: each event itself belongs to
 * the context that raises it ({@code upgrade/domain/event}, {@code tracking/domain/event},
 * {@code reflection/domain/event}), because a context's events are part of its published language.
 * See ADR-002.
 *
 * <p>Implementations are records and must be immutable — an event states something that already
 * happened, so it cannot be edited after the fact.
 */
public interface DomainEvent {

    /** When the state change this event describes occurred. */
    LocalDateTime occurredAt();
}
