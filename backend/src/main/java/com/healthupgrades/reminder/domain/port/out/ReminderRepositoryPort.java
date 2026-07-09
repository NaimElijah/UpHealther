package com.healthupgrades.reminder.domain.port.out;

import com.healthupgrades.reminder.domain.Reminder; // the aggregate this port persists

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

    /** All enabled reminders across users (used by the dispatch scheduler). */
    List<Reminder> findByEnabledTrue();
}
