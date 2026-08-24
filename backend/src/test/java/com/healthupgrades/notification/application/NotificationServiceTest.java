package com.healthupgrades.notification.application;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers FR-32 (a notification is readable afterwards however it was delivered), FR-33 (the fifty most
 * recent, an unread count, mark one and mark all) and BR-11 (an overdue upgrade is announced once,
 * however many times the sweep rediscovers it).
 *
 * <p>The push-after-commit rule has its own case below. It is the difference between a client being
 * told about a notification and a client being told about a row that then rolled back, and it is
 * invisible in every other test here because there is no transaction in a unit test unless one is
 * started deliberately.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepositoryPort repository;
    @Mock NotificationPushPort pushPort;

    @InjectMocks NotificationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void GivenANewNotification_WhenItIsCreated_ThenItIsPersistedAndPushedToItsOwner() {
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
    void GivenAnUnreadNotification_WhenItIsMarkedRead_ThenTheFlagIsFlipped() {
        Notification n = Notification.builder().id(UUID.randomUUID()).userId(userId)
                .type(NotificationType.REMINDER).category(NotificationCategory.REMINDER)
                .title("Reminder").read(false).build();
        when(repository.findByIdAndUserId(n.getId(), userId)).thenReturn(Optional.of(n));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification read = service.markRead(userId, n.getId());

        assertThat(read.isRead()).isTrue();
    }

    @Test
    void GivenAUser_WhenTheUnreadCountIsRead_ThenItComesFromTheRepository() {
        when(repository.countByUserIdAndReadFalse(userId)).thenReturn(4L);
        assertThat(service.unreadCount(userId)).isEqualTo(4L);
    }

    @Test
    void GivenAnUpgradeNotYetNotifiedAbout_WhenTheOncePerUpgradeNotificationIsCreated_ThenItIsPersistedAndPushed() {
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
    void GivenAnUpgradeAlreadyNotifiedAbout_WhenTheOncePerUpgradeNotificationIsCreated_ThenNothingIsSavedOrPushed() {
        when(repository.existsByUserIdAndRelatedUpgradeIdAndType(
                userId, upgradeId, NotificationType.UPGRADE_OVERDUE)).thenReturn(true);

        Optional<Notification> created = service.createOncePerUpgrade(userId, NotificationType.UPGRADE_OVERDUE,
                NotificationCategory.WARNING, "Upgrade overdue ⏰", () -> "Past its target date.", upgradeId);

        assertThat(created).isEmpty();
        verify(repository, never()).save(any());
        verify(pushPort, never()).push(any(), any());
    }

    @Test
    void GivenAnUpgradeAlreadyNotifiedAbout_WhenTheOncePerUpgradeNotificationIsCreated_ThenTheMessageIsNeverBuilt() {
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

    @Test
    void GivenAReadNotification_WhenItIsMarkedReadAgain_ThenNothingChanges() {
        // The client marks on click, and a double click is a double request.
        Notification alreadyRead = Notification.builder().id(UUID.randomUUID()).userId(userId)
                .type(NotificationType.REMINDER).category(NotificationCategory.REMINDER)
                .title("Reminder").read(true).build();
        when(repository.findByIdAndUserId(alreadyRead.getId(), userId)).thenReturn(Optional.of(alreadyRead));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markRead(userId, alreadyRead.getId()).isRead()).isTrue();
    }

    @Test
    void GivenANotificationBelongingToSomebodyElse_WhenItIsMarkedRead_ThenItIsReportedAsAbsentAndNotChanged() {
        UUID foreignId = UUID.randomUUID();
        when(repository.findByIdAndUserId(foreignId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(userId, foreignId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnInbox_WhenItIsListed_ThenTheFiftyMostRecentAreReturnedNewestFirst() {
        // Capped rather than paginated: anything older is not reachable through the API at all.
        List<Notification> recent = List.of(Notification.builder().id(UUID.randomUUID()).userId(userId).build());
        when(repository.findTop50ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(recent);

        assertThat(service.listRecent(userId)).isEqualTo(recent);
    }

    @Test
    void GivenManyUnreadNotifications_WhenTheyAreAllMarkedRead_ThenItIsOneBulkUpdateAndNothingIsPushed() {
        // A load-and-save loop would cost one statement per row, and would push a notification the user
        // has just dismissed.
        service.markAllRead(userId);

        verify(repository).markAllReadForUser(userId);
        verify(repository, never()).findTop50ByUserIdOrderByCreatedAtDesc(any());
        verify(repository, never()).save(any());
        verify(pushPort, never()).push(any(), any());
    }

    @Test
    void GivenATransactionIsOpen_WhenANotificationIsCreated_ThenItIsPushedOnlyAfterTheCommit() {
        // Pushing inside the transaction would let a client receive a notification whose row then rolls
        // back — visible in the UI, absent on reload, and impossible to reproduce afterwards.
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(userId, NotificationType.UPGRADE_COMPLETED, NotificationCategory.SUCCESS,
                    "Upgrade completed 🎉", "Congrats!", upgradeId);

            verify(pushPort, never()).push(any(), any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(pushPort).push(eq(userId), any(Notification.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
