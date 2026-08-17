package com.healthupgrades.notification.adapter.in.event;
import com.healthupgrades.notification.application.NotificationService;

import com.healthupgrades.upgrade.domain.event.HealthUpgradeCompleted;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCreated;
import com.healthupgrades.upgrade.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock NotificationService notificationService;
    @Mock UpgradeQuery upgradeQuery;

    @InjectMocks NotificationEventListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void completed_createsSuccessNotificationWithLookedUpTitle() {
        when(upgradeQuery.findOwned(userId, upgradeId))
                .thenReturn(Optional.of(HealthUpgrade.builder().id(upgradeId).userId(userId).title("Drink water").build()));

        listener.onCompleted(new HealthUpgradeCompleted(upgradeId, userId, LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_COMPLETED),
                eq(NotificationCategory.SUCCESS), any(), contains("Drink water"), eq(upgradeId));
    }

    @Test
    void created_usesEventTitle_noLookup() {
        listener.onCreated(new HealthUpgradeCreated(upgradeId, userId, "Walk daily", LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_CREATED),
                eq(NotificationCategory.INFO), any(), contains("Walk daily"), eq(upgradeId));
    }

    @Test
    void overdue_createsWarningOncePerUpgrade() {
        when(upgradeQuery.findOwned(userId, upgradeId))
                .thenReturn(Optional.of(HealthUpgrade.builder().id(upgradeId).userId(userId).title("Sleep early").build()));

        listener.onOverdue(new UpgradeOverdueDetected(upgradeId, userId, LocalDateTime.now()));

        // The scan rediscovers an overdue upgrade on every run, so the once-per-upgrade entry point is
        // the one that must be used here.
        verify(notificationService).createOncePerUpgrade(eq(userId), eq(NotificationType.UPGRADE_OVERDUE),
                eq(NotificationCategory.WARNING), any(), contains("Sleep early"), eq(upgradeId));
    }
}
