package com.healthupgrades.auth.domain.event;

import com.healthupgrades.common.domain.event.DomainEvent;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Raised when one server-side session is ended before its time: signed out of, or revoked because its
 * refresh credential was replayed.
 *
 * <p>An access token stops working on its next request once its session ends, because every request
 * re-reads the session. A STOMP socket makes no requests, so this is what tells it: the sockets the
 * session opened are closed on it (ADR-054). The account's other sessions are untouched, because
 * sign-out is per device.
 *
 * @param sessionId  the session that ended
 * @param userId     the account it belonged to
 * @param occurredAt when it was ended
 */
public record AuthSessionEnded(UUID sessionId, UUID userId, LocalDateTime occurredAt) implements DomainEvent {}
