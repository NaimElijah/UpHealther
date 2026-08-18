package com.healthupgrades.notification.adapter.in.web;

import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A notification as delivered to the client.
 *
 * <p>Delivered over both transports — pushed over STOMP when it is created, and returned by the REST
 * inbox afterwards — and rendered by the same component either way, which is why one mapper builds it
 * for both.
 *
 * @param id               the notification's identifier
 * @param type             what happened; the frontend maps this to an icon and a destination
 * @param category         how it should read: informational, positive, a warning, or a nudge
 * @param title            short headline, already composed server-side
 * @param message          the body, already composed server-side, may be null
 * @param relatedUpgradeId the upgrade it concerns, so the client can link to it; null for
 *                         account-wide notifications such as the daily check-in nudge
 * @param read             whether the user has acknowledged it
 * @param createdAt        when it was raised
 */
public record NotificationDto(
        UUID id,
        NotificationType type,
        NotificationCategory category,
        String title,
        String message,
        UUID relatedUpgradeId,
        boolean read,
        LocalDateTime createdAt
) {}
