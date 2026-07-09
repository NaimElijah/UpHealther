package com.healthupgrades.notification.adapter.in.web;

import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;

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
