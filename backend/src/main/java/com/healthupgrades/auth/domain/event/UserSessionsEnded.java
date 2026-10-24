package com.healthupgrades.auth.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when every session of one account is ended at once, as switching the account off does.
 *
 * <p>Names the account and no session: the revocation is one bulk update that does not report which
 * sessions it ended, and every socket the account holds is to close regardless of the session that
 * opened it (ADR-054).
 *
 * @param userId     the account whose sessions all ended
 * @param occurredAt when they were ended
 */
public record UserSessionsEnded(UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
