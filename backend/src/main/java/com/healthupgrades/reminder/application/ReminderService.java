package com.healthupgrades.reminder.application;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.reminder.application.port.in.ReminderQuery;
import com.healthupgrades.reminder.application.port.in.ReminderSchedule;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.reminder.domain.model.ReminderDays;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReminderService implements ReminderQuery {

    private final ReminderRepositoryPort repository;
    private final UpgradeQuery upgradeQuery;

    /** Creates a reminder on an owned upgrade. New reminders are enabled unless told otherwise. */
    @Transactional
    public Reminder create(UUID userId, UUID upgradeId, ReminderSchedule schedule) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId); // ownership check (throws if not owned)
        Reminder reminder = Reminder.create(upgradeId, schedule.reminderTime(),
                ReminderDays.of(schedule.days()), schedule.enabled() == null || schedule.enabled());
        return repository.save(reminder);
    }

    /** An owned upgrade's reminders. */
    public List<Reminder> getForUpgrade(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return repository.findByUpgradeId(upgradeId);
    }

    /** Reschedules an owned reminder, leaving its enabled state alone unless one is supplied. */
    @Transactional
    public Reminder update(UUID userId, UUID reminderId, ReminderSchedule schedule) {
        Reminder reminder = getOwnedReminder(userId, reminderId);
        reminder.reschedule(schedule.reminderTime(), ReminderDays.of(schedule.days()));
        if (schedule.enabled() != null) reminder.changeEnabled(schedule.enabled());
        return repository.save(reminder);
    }

    @Transactional
    public void delete(UUID userId, UUID reminderId) {
        Reminder reminder = getOwnedReminder(userId, reminderId);
        repository.delete(reminder);
    }

    private Reminder getOwnedReminder(UUID userId, UUID reminderId) {
        Reminder reminder = repository.findById(reminderId)
                .orElseThrow(() -> new ResourceNotFoundException("Reminder not found: " + reminderId));
        upgradeQuery.getOwnedUpgrade(userId, reminder.getUpgradeId()); // ownership via parent upgrade
        return reminder;
    }

    /** {@inheritDoc} Exposes enabled reminders as domain objects for the notification scheduler. */
    @Override
    public List<Reminder> findEnabled() {
        return repository.findByEnabledTrue();
    }

}
