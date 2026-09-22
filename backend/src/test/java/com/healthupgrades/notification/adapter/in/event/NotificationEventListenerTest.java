package com.healthupgrades.notification.adapter.in.event;
import com.healthupgrades.notification.application.NotificationService;

import com.healthupgrades.reflection.domain.event.ReflectionAdded;
import com.healthupgrades.tracking.domain.event.StreakAchieved;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeAbandoned;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeActivated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCompleted;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCreated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePaused;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePlanned;
import com.healthupgrades.upgrade.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * Covers the translation from domain event to notification: the category each event produces, and where
 * the upgrade title in the message comes from.
 *
 * <p>The title source is the part worth pinning. Creation carries the title on the event and must not
 * look it up, while the other events do not and must; and the overdue sweep must notify only once per
 * upgrade however many times it rediscovers the same overdue upgrade.
 */
class NotificationEventListenerTest {

    @Mock NotificationService notificationService;
    @Mock UpgradeQuery upgradeQuery;

    @InjectMocks NotificationEventListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void GivenACompletedUpgrade_WhenTheEventIsHandled_ThenASuccessNotificationCarriesTheLookedUpTitle() {
        when(upgradeQuery.findOwned(userId, upgradeId))
                .thenReturn(Optional.of(HealthUpgrade.builder().id(upgradeId).userId(userId).title("Drink water").build()));

        listener.onCompleted(new HealthUpgradeCompleted(upgradeId, userId, LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_COMPLETED),
                eq(NotificationCategory.SUCCESS), any(), contains("Drink water"), eq(upgradeId));
    }

    @Test
    void GivenACreatedUpgrade_WhenTheEventIsHandled_ThenTheEventTitleIsUsedWithoutALookup() {
        listener.onCreated(new HealthUpgradeCreated(upgradeId, userId, "Walk daily", LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_CREATED),
                eq(NotificationCategory.INFO), any(), contains("Walk daily"), eq(upgradeId));
    }

    @Test
    void GivenAPlannedUpgrade_WhenTheEventIsHandled_ThenAnInfoNotificationCarriesTheTitleAndTheStartDate() {
        givenTheUpgradeIsTitled("Stretch");

        listener.onPlanned(new HealthUpgradePlanned(upgradeId, userId, LocalDate.of(2026, 10, 1), LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_PLANNED),
                eq(NotificationCategory.INFO), any(), and(contains("Stretch"), contains("2026-10-01")), eq(upgradeId));
    }

    @Test
    void GivenAnActivatedUpgrade_WhenTheEventIsHandled_ThenASuccessNotificationCarriesTheLookedUpTitle() {
        givenTheUpgradeIsTitled("Stretch");

        listener.onActivated(new HealthUpgradeActivated(upgradeId, userId, LocalDate.of(2026, 10, 1), LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_ACTIVATED),
                eq(NotificationCategory.SUCCESS), any(), contains("Stretch"), eq(upgradeId));
    }

    @Test
    void GivenAPausedUpgrade_WhenTheEventIsHandled_ThenAnInfoNotificationCarriesTheLookedUpTitle() {
        givenTheUpgradeIsTitled("Stretch");

        listener.onPaused(new HealthUpgradePaused(upgradeId, userId, LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_PAUSED),
                eq(NotificationCategory.INFO), any(), contains("Stretch"), eq(upgradeId));
    }

    @Test
    void GivenAnAbandonedUpgrade_WhenTheEventIsHandled_ThenAnInfoNotificationCarriesTheLookedUpTitle() {
        givenTheUpgradeIsTitled("Stretch");

        listener.onAbandoned(new HealthUpgradeAbandoned(upgradeId, userId, LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_ABANDONED),
                eq(NotificationCategory.INFO), any(), contains("Stretch"), eq(upgradeId));
    }

    @Test
    void GivenAStreakMilestone_WhenTheEventIsHandled_ThenASuccessNotificationNamesTheLengthAndTheUpgrade() {
        givenTheUpgradeIsTitled("Stretch");

        listener.onStreak(new StreakAchieved(upgradeId, userId, 14, LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.STREAK_ACHIEVED),
                eq(NotificationCategory.SUCCESS), contains("14-day"), contains("Stretch"), eq(upgradeId));
    }

    @Test
    void GivenAReflection_WhenTheEventIsHandled_ThenAnInfoNotificationCarriesTheLookedUpTitle() {
        givenTheUpgradeIsTitled("Stretch");

        listener.onReflection(new ReflectionAdded(UUID.randomUUID(), upgradeId, userId, LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.REFLECTION_ADDED),
                eq(NotificationCategory.INFO), any(), contains("Stretch"), eq(upgradeId));
    }

    @Test
    void GivenTheUpgradeNoLongerExists_WhenAnEventIsHandled_ThenTheNotificationIsStillCreatedWithAGenericTitle() {
        // Deletion publishes nothing, so an event can arrive after its upgrade is gone. The user is still
        // owed the notification; failing to create it would lose it outright.
        when(upgradeQuery.findOwned(userId, upgradeId)).thenReturn(Optional.empty());

        listener.onPaused(new HealthUpgradePaused(upgradeId, userId, LocalDateTime.now()));

        verify(notificationService).create(eq(userId), eq(NotificationType.UPGRADE_PAUSED),
                eq(NotificationCategory.INFO), any(), contains("your upgrade"), eq(upgradeId));
    }

    @Test
    void GivenAnOverdueUpgrade_WhenTheEventIsHandled_ThenAWarningIsCreatedOncePerUpgrade() {
        listener.onOverdue(new UpgradeOverdueDetected(upgradeId, userId, LocalDateTime.now()));

        // The scan rediscovers an overdue upgrade on every run, so the once-per-upgrade entry point is
        // the one that must be used here.
        ArgumentCaptor<Supplier<String>> message = ArgumentCaptor.forClass(Supplier.class);
        verify(notificationService).createOncePerUpgrade(eq(userId), eq(NotificationType.UPGRADE_OVERDUE),
                eq(NotificationCategory.WARNING), any(), message.capture(), eq(upgradeId));

        // Not resolved yet: no lookup happens unless the notification is actually going to be created.
        verifyNoInteractions(upgradeQuery);

        when(upgradeQuery.findOwned(userId, upgradeId)).thenReturn(Optional.of(
                HealthUpgrade.builder().id(upgradeId).userId(userId).title("Sleep early").build()));
        assertThat(message.getValue().get()).contains("Sleep early");
    }

    private void givenTheUpgradeIsTitled(String title) {
        when(upgradeQuery.findOwned(userId, upgradeId))
                .thenReturn(Optional.of(HealthUpgrade.builder().id(upgradeId).userId(userId).title(title).build()));
    }
}
