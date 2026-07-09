package com.healthupgrades.reminder.application.port.in;

import com.healthupgrades.reminder.domain.Reminder; // returned domain aggregate

import java.util.List;

/**
 * Inbound port exposing enabled reminders as DOMAIN objects, so the notification scheduler can dispatch
 * them without reaching into the reminder persistence.
 */
public interface ReminderQuery {

    /** All enabled reminders across users (dispatch candidates). */
    List<Reminder> findEnabled();
}
