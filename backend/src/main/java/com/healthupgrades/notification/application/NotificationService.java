package com.healthupgrades.notification.application;

import com.healthupgrades.common.exception.ResourceNotFoundException;
import com.healthupgrades.notification.api.NotificationDto;
import com.healthupgrades.notification.domain.Notification;
import com.healthupgrades.notification.domain.NotificationCategory;
import com.healthupgrades.notification.domain.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    /** STOMP user-destination the frontend subscribes to (resolved per-connection via the principal). */
    public static final String USER_QUEUE = "/queue/notifications";

    private final NotificationRepositoryPort repository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Persist a notification for a user, then push it in real time to any connected session. Offline
     * users pick it up via {@link #listRecent} on their next load. This is the single entry point used
     * by both the event listener and the scheduler.
     */
    @Transactional
    public NotificationDto create(UUID userId, NotificationType type, NotificationCategory category,
                                  String title, String message, UUID relatedUpgradeId) {
        Notification notification = repository.save(Notification.builder()
                .userId(userId)
                .type(type)
                .category(category)
                .title(title)
                .message(message)
                .relatedUpgradeId(relatedUpgradeId)
                .read(false)
                .build());

        NotificationDto dto = toDto(notification);
        pushAfterCommit(userId, dto);
        return dto;
    }

    /**
     * Push only after the surrounding transaction commits, so a client never receives a real-time
     * notification for a row that then rolls back. When there is no active transaction (defensive),
     * push immediately.
     */
    private void pushAfterCommit(UUID userId, NotificationDto dto) {
        // Routed to the session(s) whose STOMP principal name == userId (see JwtChannelInterceptor).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messagingTemplate.convertAndSendToUser(userId.toString(), USER_QUEUE, dto);
                }
            });
        } else {
            messagingTemplate.convertAndSendToUser(userId.toString(), USER_QUEUE, dto);
        }
    }

    public List<NotificationDto> listRecent(UUID userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toDto).toList();
    }

    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public NotificationDto markRead(UUID userId, UUID id) {
        Notification notification = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        notification.setRead(true);
        return toDto(repository.save(notification));
    }

    @Transactional
    public void markAllRead(UUID userId) {
        repository.markAllReadForUser(userId);
    }

    private NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getCategory(), n.getTitle(), n.getMessage(),
                n.getRelatedUpgradeId(), n.isRead(), n.getCreatedAt());
    }
}
