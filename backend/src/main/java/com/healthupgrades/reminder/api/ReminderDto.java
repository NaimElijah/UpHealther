package com.healthupgrades.reminder.api;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record ReminderDto(
        UUID id,
        UUID upgradeId,
        LocalTime reminderTime,
        List<String> daysOfWeek,
        boolean enabled
) {}
