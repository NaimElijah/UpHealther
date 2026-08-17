package com.healthupgrades.notification.application;

import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationPushPort;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepositoryPort repository;
    @Mock NotificationPushPort pushPort;

    @InjectMocks NotificationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void create_persistsAndPushesToUser() {
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification created = service.create(userId, NotificationType.UPGRADE_COMPLETED,
                NotificationCategory.SUCCESS, "Upgrade completed 🎉", "Congrats!", upgradeId);

        assertThat(created.getType()).isEqualTo(NotificationType.UPGRADE_COMPLETED);
        assertThat(created.isRead()).isFalse();
        assertThat(created.getRelatedUpgradeId()).isEqualTo(upgradeId);
        verify(repository).save(any(Notification.class));
        // pushed to the user's real-time channel via the outbound push port
        verify(pushPort).push(eq(userId), any(Notification.class));
    }

    @Test
    void markRead_flipsFlag() {
        Notification n = Notification.builder().id(UUID.randomUUID()).userId(userId)
                .type(NotificationType.REMINDER).category(NotificationCategory.REMINDER)
                .title("Reminder").read(false).build();
        when(repository.findByIdAndUserId(n.getId(), userId)).thenReturn(Optional.of(n));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification read = service.markRead(userId, n.getId());

        assertThat(read.isRead()).isTrue();
    }

    @Test
    void unreadCount_delegatesToRepository() {
        when(repository.countByUserIdAndReadFalse(userId)).thenReturn(4L);
        assertThat(service.unreadCount(userId)).isEqualTo(4L);
    }

    @Test
    void createOncePerUpgrade_firstTime_persistsAndPushes() {
        when(repository.existsByUserIdAndRelatedUpgradeIdAndType(
                userId, upgradeId, NotificationType.UPGRADE_OVERDUE)).thenReturn(false);
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Notification> created = service.createOncePerUpgrade(userId, NotificationType.UPGRADE_OVERDUE,
                NotificationCategory.WARNING, "Upgrade overdue ⏰", () -> "Past its target date.", upgradeId);

        assertThat(created).isPresent();
        verify(repository).save(any(Notification.class));
        verify(pushPort).push(eq(userId), any(Notification.class));
    }

    @Test
    void createOncePerUpgrade_alreadyNotified_savesNothingAndPushesNothing() {
        when(repository.existsByUserIdAndRelatedUpgradeIdAndType(
                userId, upgradeId, NotificationType.UPGRADE_OVERDUE)).thenReturn(true);

        Optional<Notification> created = service.createOncePerUpgrade(userId, NotificationType.UPGRADE_OVERDUE,
                NotificationCategory.WARNING, "Upgrade overdue ⏰", () -> "Past its target date.", upgradeId);

        assertThat(created).isEmpty();
        verify(repository, never()).save(any());
        verify(pushPort, never()).push(any(), any());
    }

    @Test
    void createOncePerUpgrade_alreadyNotified_doesNotBuildTheMessage() {
        // Building the message costs a lookup. A permanently-overdue upgrade is rediscovered on every
        // scan, so paying for a message that is then discarded would repeat daily and indefinitely.
        when(repository.existsByUserIdAndRelatedUpgradeIdAndType(
                userId, upgradeId, NotificationType.UPGRADE_OVERDUE)).thenReturn(true);
        AtomicBoolean messageBuilt = new AtomicBoolean(false);

        service.createOncePerUpgrade(userId, NotificationType.UPGRADE_OVERDUE,
                NotificationCategory.WARNING, "Upgrade overdue ⏰",
                () -> { messageBuilt.set(true); return "Past its target date."; }, upgradeId);

        assertThat(messageBuilt).isFalse();
    }
}
