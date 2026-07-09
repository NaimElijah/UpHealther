package com.healthupgrades.reminder.adapter.in.web;

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
