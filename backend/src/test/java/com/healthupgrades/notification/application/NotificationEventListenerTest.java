package com.healthupgrades.notification.application;

import com.healthupgrades.common.events.HealthUpgradeCompleted;
import com.healthupgrades.common.events.HealthUpgradeCreated;
import com.healthupgrades.notification.domain.NotificationCategory;
import com.healthupgrades.notification.domain.NotificationType;
import com.healthupgrades.upgrade.domain.HealthUpgrade;
import com.healthupgrades.upgrade.infrastructure.UpgradeRepository;
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
    @Mock UpgradeRepository upgradeRepository;

    @InjectMocks NotificationEventListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void completed_createsSuccessNotificationWithLookedUpTitle() {
        when(upgradeRepository.findByIdAndUserId(upgradeId, userId))
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
}
