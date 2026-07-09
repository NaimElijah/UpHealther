package com.healthupgrades.common.domain.event;

/**
 * Outbound port for publishing domain events in-process.
 *
 * <p>Application services depend on this abstraction; the Spring-backed implementation lives in an
 * adapter ({@link SpringDomainEventPublisher}), so the application layer does not depend on Spring's
 * event infrastructure directly.
 */
public interface DomainEventPublisher {

    /** Publishes a domain event to any interested (in-process) listeners. */
    void publish(DomainEvent event);
}
