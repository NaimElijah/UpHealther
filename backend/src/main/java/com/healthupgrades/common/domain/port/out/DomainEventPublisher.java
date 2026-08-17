package com.healthupgrades.common.domain.port.out;

import com.healthupgrades.common.domain.event.DomainEvent;

/**
 * Outbound port for publishing domain events in-process.
 *
 * <p>Application services depend on this abstraction; the Spring-backed implementation lives in an
 * adapter ({@code SpringDomainEventPublisher}), so the application layer does not depend on Spring's
 * event infrastructure directly.
 *
 * <p>This port is cross-cutting rather than owned by one context: every context publishes through it,
 * while the events themselves belong to the context that raises them.
 */
public interface DomainEventPublisher {

    /** Publishes a domain event to any interested (in-process) listeners. */
    void publish(DomainEvent event);
}
