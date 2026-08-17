package com.healthupgrades.reminder.application;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.reminder.adapter.in.web.ReminderDto;
import com.healthupgrades.reminder.adapter.in.web.ReminderRequest;
import com.healthupgrades.reminder.application.port.in.ReminderQuery;
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

    @Transactional
    public ReminderDto create(UUID userId, UUID upgradeId, ReminderRequest req) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId); // ownership check (throws if not owned)
        Reminder reminder = Reminder.create(upgradeId, req.reminderTime(),
                ReminderDays.of(req.daysOfWeek()), req.enabled() == null || req.enabled());
        return toDto(repository.save(reminder));
    }

    public List<ReminderDto> getForUpgrade(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return repository.findByUpgradeId(upgradeId).stream().map(this::toDto).toList();
    }

    @Transactional
    public ReminderDto update(UUID userId, UUID reminderId, ReminderRequest req) {
        Reminder reminder = getOwnedReminder(userId, reminderId);
        reminder.reschedule(req.reminderTime(), ReminderDays.of(req.daysOfWeek()));
        if (req.enabled() != null) reminder.changeEnabled(req.enabled());
        return toDto(repository.save(reminder));
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

    private ReminderDto toDto(Reminder r) {
        return new ReminderDto(r.getId(), r.getUpgradeId(), r.getReminderTime(),
                r.days().toTokens(), r.isEnabled());
    }
}
