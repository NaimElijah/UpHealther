package com.healthupgrades.notification.adapter.out.messaging;

import com.healthupgrades.notification.adapter.in.web.NotificationWebMapper;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.port.out.NotificationPushPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

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
@Slf4j
public class StompNotificationPushAdapter implements NotificationPushPort {

    /** STOMP user-destination the frontend subscribes to (resolved per-connection via the principal). */
    public static final String USER_QUEUE = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate; // Spring STOMP messaging
    private final NotificationWebMapper mapper; // one rendering, shared with the REST transport

    /**
     * {@inheritDoc}
     *
     * <p><b>A failed push is degraded, not fatal, and that is a deliberate change of behaviour.</b>
     * This runs from an {@code afterCommit} callback, so the notification is already durable and
     * FR-32 — "readable afterwards regardless" — is already satisfied by the row. Letting the
     * exception out failed the caller <em>after</em> its transaction had committed, and in
     * {@code dispatchReminders} that meant one unreachable session cancelled everybody else's
     * reminders for that minute. Nothing recorded it either way.
     *
     * <p>So it is caught, narrowly, and reported at WARN with the ids needed to find the notification
     * that was not delivered. The catch handles rather than silences: the fact is stored, the failure
     * is visible, and the user sees the notification on their next load.
     */
    @Override
    public void push(UUID userId, Notification notification) {
        try {
            // Routed to the session(s) whose STOMP principal name == userId (see JwtChannelInterceptor).
            messagingTemplate.convertAndSendToUser(userId.toString(), USER_QUEUE, mapper.toDto(notification));
        } catch (MessagingException undelivered) {
            log.warn("{} {} {}", keyValue("event", "notification.push-failed"),
                    keyValue("userId", userId), keyValue("notificationId", notification.getId()),
                    undelivered);
        }
    }
}
