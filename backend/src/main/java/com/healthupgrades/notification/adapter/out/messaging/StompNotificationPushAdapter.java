package com.healthupgrades.notification.adapter.out.messaging;

import com.healthupgrades.notification.api.NotificationDto;
import com.healthupgrades.notification.domain.Notification;
import com.healthupgrades.notification.domain.port.out.NotificationPushPort;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Outbound adapter implementing {@link NotificationPushPort} over STOMP/WebSocket.
 *
 * <p>Maps the domain {@link Notification} to the same {@link NotificationDto} wire shape the frontend
 * already consumes and sends it to the user-scoped destination.
 */
@Component
@RequiredArgsConstructor
public class StompNotificationPushAdapter implements NotificationPushPort {

    /** STOMP user-destination the frontend subscribes to (resolved per-connection via the principal). */
    public static final String USER_QUEUE = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate; // Spring STOMP messaging

    /** {@inheritDoc} */
    @Override
    public void push(UUID userId, Notification notification) {
        // Routed to the session(s) whose STOMP principal name == userId (see JwtChannelInterceptor).
        messagingTemplate.convertAndSendToUser(userId.toString(), USER_QUEUE, toDto(notification));
    }

    /** Maps a notification to its wire DTO (identical payload to the REST representation). */
    private NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getCategory(), n.getTitle(), n.getMessage(),
                n.getRelatedUpgradeId(), n.isRead(), n.getCreatedAt());
    }
}
