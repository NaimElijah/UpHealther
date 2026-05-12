package com.healthupgrades.common.events;

import java.time.LocalDateTime;

public interface DomainEvent {
    LocalDateTime occurredAt();
}
