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
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Turns domain events into user notifications. Events raised inside a use case use {@code AFTER_COMMIT}
 * so a notification is only created once the originating transaction has actually committed (no
 * notifications on rollback); events raised by a scheduled scan have no transaction to wait for and use
 * a plain listener. The remaining time-based notifications (check-in, reminders) are produced directly
 * by {@code NotificationScheduler}. Per-progress entries are intentionally not notified (too frequent) —
 * streak milestones cover that.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final UpgradeQuery upgradeQuery;

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

    /**
     * A plain listener, not an {@code AFTER_COMMIT} one: the overdue scan runs outside a transaction, so
     * there is no commit to wait for and a transactional listener would never fire. The upgrade stays
     * overdue until it is dealt with, so the notification is created once per upgrade.
     */
    @EventListener
    public void onOverdue(UpgradeOverdueDetected e) {
        notificationService.createOncePerUpgrade(e.userId(), NotificationType.UPGRADE_OVERDUE,
                NotificationCategory.WARNING, "Upgrade overdue ⏰",
                "\"" + title(e.upgradeId(), e.userId()) + "\" is past its target date.", e.upgradeId());
    }

    private String title(UUID upgradeId, UUID userId) {
        return upgradeQuery.findOwned(userId, upgradeId)
                .map(HealthUpgrade::getTitle)
                .orElse("your upgrade");
    }
}
