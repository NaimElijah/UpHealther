package com.healthupgrades.reminder.adapter.in.web;

import com.healthupgrades.reminder.application.port.in.ReminderSchedule;
import com.healthupgrades.reminder.domain.model.Reminder;
import org.springframework.stereotype.Component;

import java.util.List;

/** Translates between this context's HTTP shapes and the shapes its use cases speak. */
@Component
public class ReminderWebMapper {

    /** Request record to the use-case input shape. */
    public ReminderSchedule toSchedule(ReminderRequest req) {
        return new ReminderSchedule(req.reminderTime(), req.daysOfWeek(), req.enabled());
    }

    /** Domain object to its response record; the day list is rendered by the domain type that owns it. */
    public ReminderDto toDto(Reminder r) {
        return new ReminderDto(r.getId(), r.getUpgradeId(), r.getReminderTime(),
                r.days().toTokens(), r.isEnabled());
    }

    /** Batch variant of {@link #toDto(Reminder)}. */
    public List<ReminderDto> toDtos(List<Reminder> reminders) {
        return reminders.stream().map(this::toDto).toList();
    }
}
