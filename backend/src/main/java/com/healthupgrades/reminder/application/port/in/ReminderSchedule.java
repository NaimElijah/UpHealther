package com.healthupgrades.reminder.application.port.in;

import java.time.LocalTime;
import java.util.List;

/**
 * When a reminder should fire, as supplied to a use case.
 *
 * <p>Day tokens stay as strings at this boundary because normalising them is the domain's job:
 * {@code ReminderDays} owns which spellings are accepted and how they are stored.
 *
 * @param days null or empty means every day
 * @param enabled null leaves an existing reminder's state alone and creates new ones enabled
 */
public record ReminderSchedule(
        LocalTime reminderTime,
        List<String> days,
        Boolean enabled
) {}
