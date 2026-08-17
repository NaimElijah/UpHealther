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
        // Type and timestamp only, never the payload. Events carry user-authored upgrade titles
        // ("Quit drinking", "Therapy weekly") alongside a user id, which is personal data in a health
        // application — and logging records generically would enrol every field of every future event
        // with no one reviewing the decision. Correlating a publication with its subject is the
        // consumer's job, where the fields logged are chosen deliberately.
        log.debug("Publishing domain event: {} occurred at {}",
                event.getClass().getSimpleName(), event.occurredAt());
        publisher.publishEvent(event); // hand off to Spring so existing listeners keep working
    }
}
