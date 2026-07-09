package com.healthupgrades.common.adapter.out.event;
import com.healthupgrades.common.domain.event.DomainEvent;
import com.healthupgrades.common.domain.event.DomainEventPublisher;

import lombok.RequiredArgsConstructor;
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
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher publisher; // Spring's in-process event bus

    /** {@inheritDoc} */
    @Override
    public void publish(DomainEvent event) {
        publisher.publishEvent(event); // hand off to Spring so existing listeners keep working
    }
}
