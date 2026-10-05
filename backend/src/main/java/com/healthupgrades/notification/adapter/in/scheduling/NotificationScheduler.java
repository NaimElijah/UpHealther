package com.healthupgrades.notification.adapter.in.scheduling;

import com.healthupgrades.common.observability.JobMetrics;
import com.healthupgrades.common.time.ServerZone;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Produces the delayed/scheduled notifications that are notification concerns in their own right — a
 * check-in nudge and the user's configured reminders. These call {@link NotificationService} directly
 * (rather than going through domain events) because schedulers run outside a service transaction. Crons
 * are configured under {@code app.notifications.schedules.*}.
 *
 * <p>Overdue alerts are not here: being overdue is a fact about an upgrade, so the upgrade context
 * detects it and publishes {@code UpgradeOverdueDetected}, which {@code NotificationEventListener}
 * turns into a notification.
 *
 * <p>Reads from other bounded contexts go through their inbound query ports ({@link UpgradeQuery},
 * {@link ProgressQuery}, {@link ReminderQuery}); only the notification store is accessed via its own
 * outbound port.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

    /** Stable across releases: these are metric tags and log fields, so they are a contract. */
    private static final String CHECKIN_JOB = "notification.daily-checkin";
    private static final String REMINDERS_JOB = "notification.reminder-dispatch";

    private final UpgradeQuery upgradeQuery; // inbound port: upgrades
    private final ProgressQuery progressQuery; // inbound port: progress entries
    private final ReminderQuery reminderQuery; // inbound port: enabled reminders
    private final NotificationRepositoryPort notificationRepository; // own outbound port (dedup guards)
    private final NotificationService notificationService; // own application service (create + push)
    private final JobMetrics jobMetrics; // times each run and counts how it ended
    private final Clock clock; // injectable clock for deterministic scheduling

    /**
     * Nudges every user who has running upgrades but has logged nothing today.
     *
     * <p>Two guards keep this from becoming noise: a user who has already logged something today is
     * skipped, and so is one who has already been nudged since midnight — the second matters because
     * nothing stops this cron from being configured to run more than once a day.
     */
    @Scheduled(cron = "${app.notifications.schedules.daily-checkin}", zone = ServerZone.ID)
    public void notifyDailyCheckin() {
        jobMetrics.timed(CHECKIN_JOB, () -> {
            LocalDate today = LocalDate.now(clock);
            Map<UUID, List<HealthUpgrade>> activeByUser = upgradeQuery.findByStatus(UpgradeStatus.ACTIVE).stream()
                    .collect(Collectors.groupingBy(HealthUpgrade::getUserId));

            // With nobody to nudge, the guards are not worth reading.
            int nudged = activeByUser.isEmpty() ? 0 : nudge(activeByUser, today);

            log.info("{} {} {}", keyValue("job", CHECKIN_JOB),
                    keyValue("usersWithActiveUpgrades", activeByUser.size()), keyValue("nudged", nudged));
        });
    }

    /**
     * Nudges each of the given users who has neither been nudged since midnight nor logged anything today.
     *
     * <p>Each guard is read once for the whole sweep rather than once per user (NFR-14), which keeps the
     * sweep at two queries however many users have something running.
     *
     * @param activeByUser each user with running upgrades, and those upgrades
     * @param today        the day the guards are read for
     * @return how many users were nudged
     */
    private int nudge(Map<UUID, List<HealthUpgrade>> activeByUser, LocalDate today) {
        Set<UUID> alreadyNudged = notificationRepository.findUserIdsNotifiedAfter(
                NotificationType.CHECKIN_REMINDER, today.atStartOfDay());
        Set<UUID> loggedToday = progressQuery.findUserIdsWithEntriesOn(today);

        // A count, not a list of user ids: the point is whether the nudge went out at a plausible
        // volume, and who was nudged is in their own notification list. A plain int in a plain loop -
        // this map is walked on one thread, and an atomic would advertise a concurrency requirement
        // that does not exist and send the next reader looking for it.
        int nudged = 0;
        for (Map.Entry<UUID, List<HealthUpgrade>> nudgeable : activeByUser.entrySet()) {
            UUID userId = nudgeable.getKey();
            List<HealthUpgrade> upgrades = nudgeable.getValue();
            if (!alreadyNudged.contains(userId) && !loggedToday.contains(userId)) {
                nudged++;
                notificationService.create(userId, NotificationType.CHECKIN_REMINDER, NotificationCategory.REMINDER,
                        "Daily check-in ⏳",
                        "You have " + upgrades.size() + " active upgrade" + (upgrades.size() == 1 ? "" : "s")
                                + " to track today.", null);
            }
        }
        return nudged;
    }

    /**
     * Fires the reminders that are due at this minute, for the upgrades that are active (BR-23).
     *
     * <p>A due reminder on an upgrade in any other state is silenced, not changed: the status is read
     * on every run, so pausing an upgrade quiets its reminders and activating it again brings them
     * back exactly as they were.
     *
     * <p>Runs every minute, which is what a reminder configured to the minute requires. It sweeps every
     * enabled reminder each time, so the two costly parts are avoided deliberately: due-ness is decided
     * by the reminder itself without a database round trip, and the upgrades behind the due ones are
     * loaded in one batch rather than one query each.
     *
     * <p>Unlike the check-in nudge there is no dedup guard, and none is needed: a given minute occurs
     * once, so a reminder cannot match twice.
     */
    @Scheduled(cron = "${app.notifications.schedules.reminders}", zone = ServerZone.ID)
    public void dispatchReminders() {
        jobMetrics.timed(REMINDERS_JOB, () -> {
            LocalTime now = LocalTime.now(clock);
            DayOfWeek today = LocalDate.now(clock).getDayOfWeek();

            // Whether a reminder is due is the reminder's own question to answer, not this adapter's.
            List<Reminder> due = reminderQuery.findEnabled().stream()
                    .filter(r -> r.isDueAt(today, now))
                    .toList();
            // Nothing due is the answer in fifty-nine minutes out of sixty. Logging it would write a
            // line a minute, all night, and bury the runs that did something.
            if (due.isEmpty()) return;

            // Bulk-load the related upgrades in one query instead of one lookup per reminder.
            List<UUID> upgradeIds = due.stream().map(Reminder::getUpgradeId).distinct().toList();
            Map<UUID, HealthUpgrade> upgrades = upgradeQuery.findAllById(upgradeIds).stream()
                    .collect(Collectors.toMap(HealthUpgrade::getId, Function.identity()));

            int fired = 0;
            int orphaned = 0;
            int silenced = 0;
            for (Reminder reminder : due) {
                HealthUpgrade u = upgrades.get(reminder.getUpgradeId());
                if (u == null) {
                    orphaned++;
                } else if (u.getStatus() != UpgradeStatus.ACTIVE) {
                    silenced++;
                } else {
                    fired++;
                    notificationService.create(u.getUserId(), NotificationType.REMINDER, NotificationCategory.REMINDER,
                            "Reminder ⏰", "Time for \"" + u.getTitle() + "\".", u.getId());
                }
            }

            log.info("{} {} {} {}", keyValue("job", REMINDERS_JOB), keyValue("due", due.size()),
                    keyValue("fired", fired), keyValue("silenced", silenced));

            // A reminder that outlived the upgrade it hangs off. Degraded but still serving, which
            // ADR-010 defines as WARN - leaving it as two INFO fields nobody diffs would report a real
            // inconsistency at the level reserved for things going normally. A silenced reminder is not
            // one: its upgrade is there, and BR-23 is working.
            if (orphaned > 0) {
                log.warn("{} {}", keyValue("event", "reminder.orphaned"), keyValue("count", orphaned));
            }
        });
    }

}
