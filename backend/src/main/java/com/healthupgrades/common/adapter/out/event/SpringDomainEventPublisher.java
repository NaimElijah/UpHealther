package com.healthupgrades.common.adapter.out.event;
import com.healthupgrades.common.domain.event.DomainEvent;
import com.healthupgrades.common.domain.port.out.DomainEventPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing {@link DomainEventPublisher} over Spring's {@link ApplicationEventPublisher}.
 *
 * <p>Delegating to the real Spring publisher is important: it keeps transaction-bound consumers
 * ({@code @TransactionalEventListener(AFTER_COMMIT)}) firing exactly as before.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher publisher; // Spring's in-process event bus

    /** {@inheritDoc} */
    @Override
    public void publish(DomainEvent event) {
        // Logged here rather than in a listener per event type: this cannot fall behind the event
        // vocabulary, and it records the publication itself rather than one consumer's view of it.
        // Events are records, so their toString already carries the payload.
        log.debug("Publishing domain event: {}", event);
        publisher.publishEvent(event); // hand off to Spring so existing listeners keep working
    }
}
