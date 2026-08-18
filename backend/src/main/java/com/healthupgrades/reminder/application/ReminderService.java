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

/**
 * Use cases for reminders: attach one to an upgrade, reschedule it, remove it, and expose the enabled
 * ones for dispatch.
 *
 * <p>A reminder has no owner of its own — ownership is established through the upgrade it belongs to,
 * which is why every method here starts by resolving that upgrade through {@link UpgradeQuery}.
 */
@Service
@RequiredArgsConstructor
public class ReminderService implements ReminderQuery {

    private final ReminderRepositoryPort repository;
    private final UpgradeQuery upgradeQuery;

    /**
     * Attaches a reminder to an owned upgrade.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade to attach it to
     * @param schedule  when it should fire; a null enabled flag creates it enabled
     * @return the persisted reminder
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if a day token is not a
     *         recognisable day
     */
    @Transactional
    public Reminder create(UUID userId, UUID upgradeId, ReminderSchedule schedule) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId); // ownership check (throws if not owned)
        Reminder reminder = Reminder.create(upgradeId, schedule.reminderTime(),
                ReminderDays.of(schedule.days()), schedule.enabled() == null || schedule.enabled());
        return repository.save(reminder);
    }

    /**
     * Reads an owned upgrade's reminders, enabled or not.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade to read
     * @return its reminders; empty when none are configured
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     */
    public List<Reminder> getForUpgrade(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return repository.findByUpgradeId(upgradeId);
    }

    /**
     * Reschedules an owned reminder, and switches it on or off when the schedule says so.
     *
     * @param userId     the owner
     * @param reminderId the reminder to change
     * @param schedule   the new time and days; a null enabled flag leaves the current state alone
     * @return the saved reminder
     * @throws ResourceNotFoundException if the reminder does not exist, or its upgrade is not the user's
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if a day token is not a
     *         recognisable day
     */
    @Transactional
    public Reminder update(UUID userId, UUID reminderId, ReminderSchedule schedule) {
        Reminder reminder = getOwnedReminder(userId, reminderId);
        reminder.reschedule(schedule.reminderTime(), ReminderDays.of(schedule.days()));
        if (schedule.enabled() != null) reminder.changeEnabled(schedule.enabled());
        return repository.save(reminder);
    }

    /**
     * Deletes an owned reminder.
     *
     * @param userId     the owner
     * @param reminderId the reminder to remove
     * @throws ResourceNotFoundException if the reminder does not exist, or its upgrade is not the user's
     */
    @Transactional
    public void delete(UUID userId, UUID reminderId) {
        Reminder reminder = getOwnedReminder(userId, reminderId);
        repository.delete(reminder);
    }

    /** Single ownership guard: a reminder is the caller's only if the upgrade it hangs off is. */
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
