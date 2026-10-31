package com.healthupgrades.reminder.application.port.in;

import com.healthupgrades.reminder.domain.model.Reminder; // returned domain aggregate
import java.time.LocalTime;
import java.util.List;

/**
 * Inbound port for the scheduled sweep that dispatches reminders across every user, so it can read them
 * as domain objects without reaching into the reminder persistence.
 *
 * <p><strong>Every user's rows, not one user's.</strong> Nothing here is scoped by an owner, so it exists
 * only for scheduled work, and only the jobs' application services may depend on it, which
 * {@code HexagonalArchitectureTest} enforces. A request-driven read takes the owner's id and lives on
 * the context's user-facing query port instead (BR-15).
 */
public interface ReminderSweepQuery {

    /**
     * Every enabled reminder set for a time within the minute {@code time} falls in, whoever owns it: the
     * dispatch candidates. Whether one is due on today's day is the reminder's own question.
     *
     * @param time a time of day, of which only the hour and minute count
     * @return the enabled reminders of every user set within that minute
     */
    List<Reminder> findEnabledWithinMinute(LocalTime time);
}
