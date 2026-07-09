package com.healthupgrades.notification.application;

import com.healthupgrades.common.events.*;
import com.healthupgrades.notification.domain.NotificationCategory;
import com.healthupgrades.notification.domain.NotificationType;
import com.healthupgrades.upgrade.domain.HealthUpgrade;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Turns domain events into user notifications. Uses {@code AFTER_COMMIT} so a notification is only
 * created once the originating action's transaction has actually committed (no notifications on
 * rollback). Scheduled/delayed notifications (overdue, check-in, reminders) are produced directly by
 * {@code NotificationScheduler}, not here. Per-progress entries are intentionally not notified (too
 * frequent) — streak milestones cover that.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final UpgradeRepositoryPort upgradeRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(HealthUpgradeCreated e) {
        notificationService.create(e.userId(), NotificationType.UPGRADE_CREATED, NotificationCategory.INFO,
                "New upgrade idea", "\"" + e.title() + "\" was added to your backlog.", e.upgradeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanned(HealthUpgradePlanned e) {
        notificationService.create(e.userId(), NotificationType.UPGRADE_PLANNED, NotificationCategory.INFO,
                "Upgrade planned", "\"" + title(e.upgradeId(), e.userId()) + "\" is planned to start "
                        + e.plannedStartDate() + ".", e.upgradeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(HealthUpgradeActivated e) {
        notificationService.create(e.userId(), NotificationType.UPGRADE_ACTIVATED, NotificationCategory.SUCCESS,
                "Upgrade activated 💪", "You activated \"" + title(e.upgradeId(), e.userId()) + "\". Let's go!",
                e.upgradeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaused(HealthUpgradePaused e) {
        notificationService.create(e.userId(), NotificationType.UPGRADE_PAUSED, NotificationCategory.INFO,
                "Upgrade paused", "\"" + title(e.upgradeId(), e.userId()) + "\" is paused.", e.upgradeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(HealthUpgradeCompleted e) {
        notificationService.create(e.userId(), NotificationType.UPGRADE_COMPLETED, NotificationCategory.SUCCESS,
                "Upgrade completed 🎉", "Congrats! You completed \"" + title(e.upgradeId(), e.userId()) + "\".",
                e.upgradeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAbandoned(HealthUpgradeAbandoned e) {
        notificationService.create(e.userId(), NotificationType.UPGRADE_ABANDONED, NotificationCategory.INFO,
                "Upgrade abandoned", "\"" + title(e.upgradeId(), e.userId()) + "\" was abandoned.", e.upgradeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStreak(StreakAchieved e) {
        notificationService.create(e.userId(), NotificationType.STREAK_ACHIEVED, NotificationCategory.SUCCESS,
                "🔥 " + e.streakDays() + "-day streak!",
                "\"" + title(e.upgradeId(), e.userId()) + "\" — keep the momentum going.", e.upgradeId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReflection(ReflectionAdded e) {
        notificationService.create(e.userId(), NotificationType.REFLECTION_ADDED, NotificationCategory.INFO,
                "Reflection added", "You reflected on \"" + title(e.upgradeId(), e.userId()) + "\".", e.upgradeId());
    }

    private String title(UUID upgradeId, UUID userId) {
        return upgradeRepository.findByIdAndUserId(upgradeId, userId)
                .map(HealthUpgrade::getTitle)
                .orElse("your upgrade");
    }
}
