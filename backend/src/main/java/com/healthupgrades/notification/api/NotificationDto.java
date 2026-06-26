package com.healthupgrades.notification.api;

import com.healthupgrades.notification.domain.NotificationCategory;
import com.healthupgrades.notification.domain.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

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
