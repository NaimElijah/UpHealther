package com.healthupgrades.notification.adapter.out.persistence;

import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository backing {@link NotificationRepositoryAdapter}; package-private internal detail.
 */
interface NotificationJpaRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndReadFalse(UUID userId);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    // Dedup guard for system-generated notifications (e.g. one "overdue" per upgrade per target date).
    boolean existsByUserIdAndRelatedUpgradeIdAndTypeAndCreatedAtGreaterThanEqual(
            UUID userId, UUID relatedUpgradeId, NotificationType type, LocalDateTime since);

    // Dedup guard for the once-per-day check-in nudge, for every user at once.
    @Query("select distinct n.userId from Notification n where n.type = :type and n.createdAt > :after")
    Set<UUID> findUserIdsNotifiedAfter(@Param("type") NotificationType type, @Param("after") LocalDateTime after);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    void markAllReadForUser(@Param("userId") UUID userId);

    /**
     * Deletes a batch of the oldest notifications created before the cutoff, of every user.
     *
     * <p>Native, because JPQL's bulk delete takes no limit. {@code REQUIRES_NEW}, so each batch commits on
     * its own whatever transaction the caller runs in: a long backlog is then deleted in short transactions
     * rather than one that holds its locks for the whole table, and a caller that wrapped the run in a
     * transaction of its own cannot turn it back into one (ADR-075).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "DELETE FROM notifications WHERE id IN"
                    + " (SELECT id FROM notifications WHERE created_at < :cutoff ORDER BY created_at LIMIT :limit)",
            nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
