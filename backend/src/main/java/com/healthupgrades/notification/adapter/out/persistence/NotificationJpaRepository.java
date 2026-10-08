package com.healthupgrades.notification.adapter.out.persistence;

import com.healthupgrades.notification.domain.model.Notification; // managed entity
import com.healthupgrades.notification.domain.model.NotificationType; // dedup-guard parameter
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data JPA repository backing {@link NotificationRepositoryAdapter}; package-private internal detail.
 * Query methods (including the bulk update) are kept verbatim from the previous infrastructure repository.
 */
interface NotificationJpaRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId); // derived query, newest first

    long countByUserIdAndReadFalse(UUID userId); // derived count of unread

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId); // derived query: ownership-scoped

    // Dedup guard for system-generated notifications (e.g. one "overdue" per upgrade per target date).
    boolean existsByUserIdAndRelatedUpgradeIdAndTypeAndCreatedAtGreaterThanEqual(
            UUID userId, UUID relatedUpgradeId, NotificationType type, LocalDateTime since);

    // Dedup guard for the once-per-day check-in nudge, for every user at once.
    @Query("select distinct n.userId from Notification n where n.type = :type and n.createdAt > :after")
    Set<UUID> findUserIdsNotifiedAfter(@Param("type") NotificationType type, @Param("after") LocalDateTime after);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    void markAllReadForUser(@Param("userId") UUID userId); // bulk mark-as-read
}
