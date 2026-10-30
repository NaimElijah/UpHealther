package com.healthupgrades.notification.adapter.in.scheduling;

import static com.healthupgrades.notification.application.NotificationSweepService.CHECKIN_JOB;
import static com.healthupgrades.notification.application.NotificationSweepService.REMINDERS_JOB;

import com.healthupgrades.common.observability.JobMetrics;
import com.healthupgrades.common.time.ServerZone;
import com.healthupgrades.notification.application.NotificationSweepService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter that runs the notification sweeps on their crons: the daily check-in nudge and the
 * reminder dispatch, both in {@link NotificationSweepService}. Crons are configured under
 * {@code app.notifications.schedules.*}.
 *
 * <p>A trigger and nothing more. It reads the clock in the server's zone, times each run and counts how
 * it ended under the job's name (NFR-25), and leaves every decision about who is notified to the use
 * case, so a second trigger would have one to call.
 */
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationSweepService sweeps; // own application service: the two sweeps
    private final JobMetrics jobMetrics; // times each run and counts how it ended
    private final Clock clock; // injectable clock for deterministic scheduling

    /** Nudges every user who has running upgrades but has logged nothing today. */
    @Scheduled(cron = "${app.notifications.schedules.daily-checkin}", zone = ServerZone.ID)
    public void notifyDailyCheckin() {
        jobMetrics.timed(CHECKIN_JOB, () -> sweeps.nudgeDailyCheckin(LocalDate.now(clock)));
    }

    /** Fires the reminders that are due at this minute, for the upgrades that are active (BR-23). */
    @Scheduled(cron = "${app.notifications.schedules.reminders}", zone = ServerZone.ID)
    public void dispatchReminders() {
        jobMetrics.timed(REMINDERS_JOB, () -> {
            LocalTime now = LocalTime.now(clock);
            DayOfWeek today = LocalDate.now(clock).getDayOfWeek();
            sweeps.dispatchDueReminders(today, now);
        });
    }
}
