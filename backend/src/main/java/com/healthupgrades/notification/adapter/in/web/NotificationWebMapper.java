package com.healthupgrades.notification.adapter.in.web;

import com.healthupgrades.notification.domain.model.Notification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders a notification into the shape clients receive.
 *
 * <p>Shared by both transports on purpose. The real-time push and the REST list must deliver an
 * identical payload — the frontend renders whichever arrives first with the same component — and that
 * agreement was previously maintained by two copies of the same mapping, one here and one in the STOMP
 * adapter, with nothing keeping them in step.
 */
@Component
public class NotificationWebMapper {

    /** Domain object to its response record. */
    public NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getCategory(), n.getTitle(), n.getMessage(),
                n.getRelatedUpgradeId(), n.isRead(), n.getCreatedAt());
    }

    /** Batch variant of {@link #toDto(Notification)}. */
    public List<NotificationDto> toDtos(List<Notification> notifications) {
        return notifications.stream().map(this::toDto).toList();
    }
}
