package com.healthupgrades.reminder.domain.port.out;

import com.healthupgrades.reminder.domain.model.Reminder; // the aggregate this port persists
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and querying {@link Reminder} aggregates.
 */
public interface ReminderRepositoryPort {

    /** Persists a new or updated reminder and returns the managed instance. */
    Reminder save(Reminder reminder);

    /** Removes a reminder from the store. */
    void delete(Reminder reminder);

    /** A single reminder by id (ownership is enforced via the parent upgrade in the service). */
    Optional<Reminder> findById(UUID id);

    /** All reminders configured for an upgrade. */
    List<Reminder> findByUpgradeId(UUID upgradeId);

    /**
     * The enabled reminders across users set for a time within the minute {@code time} falls in, from its
     * first instant to its last, so the minute before midnight does not wrap into the next day's first.
     *
     * @param time a time of day, of which only the hour and minute count
     * @return those reminders, for the dispatch sweep
     */
    List<Reminder> findEnabledWithinMinute(LocalTime time);
}
