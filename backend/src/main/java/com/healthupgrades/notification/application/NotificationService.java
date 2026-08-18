package com.healthupgrades.notification.application;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationPushPort;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Application service for notifications: persistence, reads, and coordinating the real-time push.
 *
 * <p>The transport is behind {@link NotificationPushPort}; this service only decides <em>when</em> to
 * push (after the surrounding transaction commits).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepositoryPort repository; // outbound persistence port
    private final NotificationPushPort pushPort; // outbound real-time delivery port

    /**
     * Persist a notification for a user, then push it in real time to any connected session. Offline
     * users pick it up via {@link #listRecent} on their next load. This is the single entry point used
     * by both the event listener and the scheduler.
     */
    @Transactional
    public Notification create(UUID userId, NotificationType type, NotificationCategory category,
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

        pushAfterCommit(userId, notification);
        return notification;
    }

    /**
     * Creates a notification unless the user has already been told this about this upgrade.
     *
     * <p>For facts a periodic scan rediscovers on every run — an overdue upgrade stays overdue until it
     * is dealt with — so the user hears about it once instead of on every sweep.
     *
     * <p>The message is a {@link Supplier} because building it usually costs a lookup, and for the
     * already-notified case — which is every run after the first, indefinitely — that lookup would be
     * discarded.
     *
     * @return the new notification, or empty when one already existed
     */
    @Transactional
    public Optional<Notification> createOncePerUpgrade(UUID userId, NotificationType type,
                                                       NotificationCategory category, String title,
                                                       Supplier<String> message, UUID relatedUpgradeId) {
        if (repository.existsByUserIdAndRelatedUpgradeIdAndType(userId, relatedUpgradeId, type)) {
            return Optional.empty();
        }
        return Optional.of(create(userId, type, category, title, message.get(), relatedUpgradeId));
    }

    /**
     * Push only after the surrounding transaction commits, so a client never receives a real-time
     * notification for a row that then rolls back. When there is no active transaction (defensive),
     * push immediately.
     */
    private void pushAfterCommit(UUID userId, Notification notification) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pushPort.push(userId, notification);
                }
            });
        } else {
            pushPort.push(userId, notification);
        }
    }

    /**
     * The user's fifty most recent notifications, newest first.
     *
     * <p>Capped rather than paginated: this backs an inbox of recent activity, and older notifications
     * are not retrievable through the API at all.
     *
     * @param userId the owner
     * @return up to fifty notifications, read and unread alike
     */
    public List<Notification> listRecent(UUID userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Counts the user's unread notifications.
     *
     * <p>Counts all of them, not only the fifty {@link #listRecent} returns, so the badge can exceed
     * what the list shows.
     *
     * @param userId the owner
     * @return the number unread, zero when there are none
     */
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    /**
     * Marks one of the user's notifications as read. Idempotent — marking a read one again is a no-op.
     *
     * @param userId the owner
     * @param id     the notification to acknowledge
     * @return the saved notification
     * @throws ResourceNotFoundException if the notification does not exist or belongs to somebody else
     */
    @Transactional
    public Notification markRead(UUID userId, UUID id) {
        Notification notification = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        notification.markAsRead();
        return repository.save(notification);
    }

    /**
     * Marks every one of the user's unread notifications as read.
     *
     * <p>A single bulk update rather than a load-and-save loop, so the cost does not grow with the size
     * of the inbox. The entities are therefore not loaded, and no push follows.
     *
     * @param userId the owner
     */
    @Transactional
    public void markAllRead(UUID userId) {
        repository.markAllReadForUser(userId);
    }

}
