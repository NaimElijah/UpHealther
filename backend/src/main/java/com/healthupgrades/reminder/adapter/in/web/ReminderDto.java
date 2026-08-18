package com.healthupgrades.reminder.adapter.in.web;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A reminder as returned on the wire.
 *
 * <p>{@code daysOfWeek} is rendered from the domain's own day type, so what a client reads back is the
 * normalised three-letter form regardless of how it was written. An empty list means every day.
 *
 * @param id           the reminder's identifier
 * @param upgradeId    the upgrade it belongs to
 * @param reminderTime the local time it fires at
 * @param daysOfWeek   the days it fires on, in calendar order; empty means every day
 * @param enabled      whether it is switched on
 */
public record ReminderDto(
        UUID id,
        UUID upgradeId,
        LocalTime reminderTime,
        List<String> daysOfWeek,
        boolean enabled
) {}
