package com.healthupgrades.reminder.adapter.out.persistence;

import com.healthupgrades.reminder.domain.Reminder; // domain aggregate
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort; // the port implemented here
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link ReminderRepositoryPort} by delegating to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
class ReminderRepositoryAdapter implements ReminderRepositoryPort {

    private final ReminderJpaRepository jpa; // Spring Data proxy

    @Override
    public Reminder save(Reminder reminder) {
        return jpa.save(reminder); // delegate persist
    }

    @Override
    public void delete(Reminder reminder) {
        jpa.delete(reminder); // delegate delete
    }

    @Override
    public Optional<Reminder> findById(UUID id) {
        return jpa.findById(id); // delegate to inherited lookup
    }

    @Override
    public List<Reminder> findByUpgradeId(UUID upgradeId) {
        return jpa.findByUpgradeId(upgradeId); // delegate
    }

    @Override
    public List<Reminder> findByEnabledTrue() {
        return jpa.findByEnabledTrue(); // delegate
    }
}
