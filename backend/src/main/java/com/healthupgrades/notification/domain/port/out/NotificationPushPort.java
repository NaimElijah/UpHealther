package com.healthupgrades.notification.domain.port.out;

import com.healthupgrades.notification.domain.Notification; // domain object pushed to the user

import java.util.UUID;

/**
 * Outbound port for delivering a notification to a user in real time.
 *
 * <p>Keeps the transport (STOMP/WebSocket today) out of the application service — the service depends on
 * this abstraction and an adapter performs the actual push.
 */
public interface NotificationPushPort {

    /** Pushes a notification to the given user's real-time channel. */
    void push(UUID userId, Notification notification);
}
