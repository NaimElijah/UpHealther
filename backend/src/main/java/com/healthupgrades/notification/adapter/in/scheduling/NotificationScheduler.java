package com.healthupgrades.notification.adapter.in.scheduling;

import com.healthupgrades.notification.application.NotificationService;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.reminder.application.port.in.ReminderQuery;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Produces the delayed/scheduled notifications. These call {@link NotificationService} directly (rather
 * than going through domain events) because schedulers run outside a service transaction. Crons are
 * configured under {@code app.notifications.schedules.*}.
 *
 * <p>Reads from other bounded contexts go through their inbound query ports ({@link UpgradeQuery},
 * {@link ProgressQuery}, {@link ReminderQuery}); only the notification store is accessed via its own
 * outbound port.
 */
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final UpgradeQuery upgradeQuery; // inbound port: upgrades
    private final ProgressQuery progressQuery; // inbound port: progress entries
    private final ReminderQuery reminderQuery; // inbound port: enabled reminders
    private final NotificationRepositoryPort notificationRepository; // own outbound port (dedup guards)
    private final NotificationService notificationService; // own application service (create + push)
    private final Clock clock; // injectable clock for deterministic scheduling

    /** Alerts the owner once when an active upgrade passes its target date. */
    @Scheduled(cron = "${app.notifications.schedules.overdue}")
    public void notifyOverdue() {
        for (HealthUpgrade u : upgradeQuery.findByStatus(UpgradeStatus.ACTIVE)) {
            if (u.isOverdue() && !notificationRepository.existsByUserIdAndRelatedUpgradeIdAndType(
                    u.getUserId(), u.getId(), NotificationType.UPGRADE_OVERDUE)) {
                notificationService.create(u.getUserId(), NotificationType.UPGRADE_OVERDUE,
                        NotificationCategory.WARNING, "Upgrade overdue ⏰",
                        "\"" + u.getTitle() + "\" is past its target date.", u.getId());
            }
        }
    }

    /** Nudges users with active upgrades who haven't logged any progress today (once per day). */
    @Scheduled(cron = "${app.notifications.schedules.daily-checkin}")
    public void notifyDailyCheckin() {
        LocalDate today = LocalDate.now(clock);
        Map<UUID, List<HealthUpgrade>> activeByUser = upgradeQuery.findByStatus(UpgradeStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(HealthUpgrade::getUserId));

        activeByUser.forEach((userId, upgrades) -> {
            boolean alreadyNudged = notificationRepository.existsByUserIdAndTypeAndCreatedAtAfter(
                    userId, NotificationType.CHECKIN_REMINDER, today.atStartOfDay());
            boolean loggedToday = !progressQuery.findByUserIdAndDate(userId, today).isEmpty();
            if (!alreadyNudged && !loggedToday) {
                notificationService.create(userId, NotificationType.CHECKIN_REMINDER, NotificationCategory.REMINDER,
                        "Daily check-in ⏳",
                        "You have " + upgrades.size() + " active upgrade" + (upgrades.size() == 1 ? "" : "s")
                                + " to track today.", null);
            }
        });
    }

    /** Dispatches user-configured per-upgrade reminders whose time/day matches now (runs every minute). */
    @Scheduled(cron = "${app.notifications.schedules.reminders}")
    public void dispatchReminders() {
        LocalTime now = LocalTime.now(clock);
        String todayShort = LocalDate.now(clock).getDayOfWeek().name().substring(0, 3); // e.g. MONDAY -> MON

        List<Reminder> due = reminderQuery.findEnabled().stream()
                .filter(r -> isDueNow(r.getReminderTime(), now))
                .filter(r -> dayMatches(r.getDaysOfWeek(), todayShort))
                .toList();
        if (due.isEmpty()) return;

        // Bulk-load the related upgrades in one query instead of one lookup per reminder.
        List<UUID> upgradeIds = due.stream().map(Reminder::getUpgradeId).distinct().toList();
        Map<UUID, HealthUpgrade> upgrades = upgradeQuery.findAllById(upgradeIds).stream()
                .collect(Collectors.toMap(HealthUpgrade::getId, Function.identity()));

        for (Reminder reminder : due) {
            HealthUpgrade u = upgrades.get(reminder.getUpgradeId());
            if (u != null) {
                notificationService.create(u.getUserId(), NotificationType.REMINDER, NotificationCategory.REMINDER,
                        "Reminder ⏰", "Time for \"" + u.getTitle() + "\".", u.getId());
            }
        }
    }

    /** True when the reminder's time matches the current hour and minute. */
    private boolean isDueNow(LocalTime time, LocalTime now) {
        return time != null && time.getHour() == now.getHour() && time.getMinute() == now.getMinute();
    }

    /** A blank/empty day list means "every day"; otherwise the CSV must contain today (e.g. MON,WED,FRI). */
    private boolean dayMatches(String daysOfWeek, String todayShort) {
        if (daysOfWeek == null || daysOfWeek.isBlank()) return true;
        return Arrays.stream(daysOfWeek.split(","))
                .map(s -> s.trim().toUpperCase())
                .anyMatch(todayShort::equals);
    }
}
