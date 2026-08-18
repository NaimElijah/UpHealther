package com.healthupgrades.reminder.adapter.in.web;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.List;

/**
 * Request body for creating or rescheduling a reminder.
 *
 * <p>Day tokens are accepted in the three-letter or full form, in any case, but an unrecognisable one
 * is rejected rather than dropped — silently discarding it would leave an empty filter, which means
 * every day, and turn a twice-weekly reminder into a daily one.
 *
 * @param reminderTime the local time to fire at; required
 * @param daysOfWeek   the days to fire on, e.g. {@code ["MON","WED","FRI"]}; null or empty means daily
 * @param enabled      whether it is switched on; null creates it enabled and leaves an existing one
 *                     unchanged
 */
public record ReminderRequest(
        @NotNull LocalTime reminderTime,
        List<String> daysOfWeek,   // e.g. ["MON","WED","FRI"]; empty/null means every day
        Boolean enabled
) {}
