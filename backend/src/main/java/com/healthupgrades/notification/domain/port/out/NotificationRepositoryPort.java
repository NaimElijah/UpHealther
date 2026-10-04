package com.healthupgrades.notification.domain.port.out;

import com.healthupgrades.notification.domain.model.Notification; // the aggregate this port persists
import com.healthupgrades.notification.domain.model.NotificationType; // used by the dedup guards

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port for persisting and querying {@link Notification} aggregates.
 */
public interface NotificationRepositoryPort {

    /** Persists a new or updated notification and returns the managed instance. */
    Notification save(Notification notification);

    /** A user's 50 most recent notifications, newest first. */
    List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Count of a user's unread notifications. */
    long countByUserIdAndReadFalse(UUID userId);

    /** Ownership-scoped single lookup by id. */
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    /** Dedup guard: whether a system notification of a type already exists for an upgrade. */
    boolean existsByUserIdAndRelatedUpgradeIdAndType(UUID userId, UUID relatedUpgradeId, NotificationType type);

    /**
     * Dedup guard for a sweep: every user sent a notification of a type after a timestamp, in one query
     * rather than one per user (NFR-14).
     */
    Set<UUID> findUserIdsNotifiedAfter(NotificationType type, LocalDateTime after);

    /** Marks all of a user's unread notifications as read in a single bulk update. */
    void markAllReadForUser(UUID userId);
}
