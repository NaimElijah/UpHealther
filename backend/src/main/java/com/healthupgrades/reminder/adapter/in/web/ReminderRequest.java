package com.healthupgrades.reminder.adapter.in.web;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.List;

public record ReminderRequest(
        @NotNull LocalTime reminderTime,
        List<String> daysOfWeek,   // e.g. ["MON","WED","FRI"]; empty/null means every day
        Boolean enabled
) {}
