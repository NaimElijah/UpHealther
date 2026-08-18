package com.healthupgrades.notification.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A message raised for a user by the system.
 *
 * <p>Never created by a user action directly: notifications are produced by the event listener and the
 * scheduler, which is why the only state change the aggregate offers is {@link #markAsRead()}. The title
 * and message are composed when it is raised and stored as text, so a notification keeps saying what it
 * said even after the upgrade behind it is renamed or deleted.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for general use
@AllArgsConstructor(access = AccessLevel.PRIVATE) // backs the builder only
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationCategory category;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** The upgrade this concerns, letting the client link to it. Null for account-wide notifications. */
    private UUID relatedUpgradeId;

    // Mapped to the column "is_read" because "read" is a reserved word in several SQL dialects.
    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Marks the notification as read. */
    public void markAsRead() {
        this.read = true;
    }

    /**
     * Stamps the creation timestamp, unless one was supplied.
     *
     * <p>Conditional, unlike the other entities' callbacks: notifications are the one aggregate whose
     * timestamp a caller may set deliberately, and overwriting it here would discard that.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
