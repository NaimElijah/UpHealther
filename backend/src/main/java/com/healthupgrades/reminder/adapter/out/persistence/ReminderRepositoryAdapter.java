package com.healthupgrades.reminder.adapter.out.persistence;

import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Persistence adapter implementing {@link ReminderRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class ReminderRepositoryAdapter implements ReminderRepositoryPort {

    private final ReminderJpaRepository jpa;

    /** {@inheritDoc} */
    @Override
    public Reminder save(Reminder reminder) {
        return jpa.save(reminder);
    }

    /** {@inheritDoc} */
    @Override
    public void delete(Reminder reminder) {
        jpa.delete(reminder);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Reminder> findById(UUID id) {
        return jpa.findById(id);
    }

    /** {@inheritDoc} */
    @Override
    public List<Reminder> findByUpgradeId(UUID upgradeId) {
        return jpa.findByUpgradeId(upgradeId);
    }

    /** {@inheritDoc} */
    @Override
    public List<Reminder> findEnabledWithinMinute(LocalTime time) {
        return jpa.findEnabledWithinMinute(time.getHour(), time.getMinute());
    }
}
