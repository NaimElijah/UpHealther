package com.healthupgrades.notification.adapter.out.messaging;

import com.healthupgrades.notification.adapter.in.web.NotificationWebMapper;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.port.out.NotificationPushPort;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Outbound adapter implementing {@link NotificationPushPort} over STOMP/WebSocket.
 *
 * <p>Sends the same payload the REST endpoints return, via the shared {@link NotificationWebMapper},
 * so the two transports cannot drift apart — the frontend renders whichever arrives first with the
 * same component.
 */
@Component
@RequiredArgsConstructor
public class StompNotificationPushAdapter implements NotificationPushPort {

    /** STOMP user-destination the frontend subscribes to (resolved per-connection via the principal). */
    public static final String USER_QUEUE = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate; // Spring STOMP messaging
    private final NotificationWebMapper mapper; // one rendering, shared with the REST transport

    /** {@inheritDoc} */
    @Override
    public void push(UUID userId, Notification notification) {
        // Routed to the session(s) whose STOMP principal name == userId (see JwtChannelInterceptor).
        messagingTemplate.convertAndSendToUser(userId.toString(), USER_QUEUE, mapper.toDto(notification));
    }
}
