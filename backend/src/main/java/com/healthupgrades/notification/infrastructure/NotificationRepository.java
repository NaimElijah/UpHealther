package com.healthupgrades.notification.infrastructure;

import com.healthupgrades.notification.domain.Notification;
import com.healthupgrades.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndReadFalse(UUID userId);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    // Dedup guard for system-generated notifications (e.g. only one "overdue" per upgrade).
    boolean existsByUserIdAndRelatedUpgradeIdAndType(UUID userId, UUID relatedUpgradeId, NotificationType type);

    // Dedup guard for the once-per-day check-in nudge.
    boolean existsByUserIdAndTypeAndCreatedAtAfter(UUID userId, NotificationType type, LocalDateTime after);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    void markAllReadForUser(@Param("userId") UUID userId);
}
