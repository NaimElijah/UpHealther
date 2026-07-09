package com.healthupgrades.notification.adapter.out.persistence;

import com.healthupgrades.notification.domain.Notification; // domain aggregate
import com.healthupgrades.notification.domain.NotificationType; // dedup-guard parameter
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link NotificationRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class NotificationRepositoryAdapter implements NotificationRepositoryPort {

    private final NotificationJpaRepository jpa; // Spring Data proxy

    @Override
    public Notification save(Notification notification) {
        return jpa.save(notification); // delegate persist
    }

    @Override
    public List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId) {
        return jpa.findTop50ByUserIdOrderByCreatedAtDesc(userId); // delegate
    }

    @Override
    public long countByUserIdAndReadFalse(UUID userId) {
        return jpa.countByUserIdAndReadFalse(userId); // delegate unread count
    }

    @Override
    public Optional<Notification> findByIdAndUserId(UUID id, UUID userId) {
        return jpa.findByIdAndUserId(id, userId); // delegate ownership-scoped lookup
    }

    @Override
    public boolean existsByUserIdAndRelatedUpgradeIdAndType(UUID userId, UUID relatedUpgradeId, NotificationType type) {
        return jpa.existsByUserIdAndRelatedUpgradeIdAndType(userId, relatedUpgradeId, type); // delegate dedup check
    }

    @Override
    public boolean existsByUserIdAndTypeAndCreatedAtAfter(UUID userId, NotificationType type, LocalDateTime after) {
        return jpa.existsByUserIdAndTypeAndCreatedAtAfter(userId, type, after); // delegate dedup check
    }

    @Override
    public void markAllReadForUser(UUID userId) {
        jpa.markAllReadForUser(userId); // delegate bulk update
    }
}
