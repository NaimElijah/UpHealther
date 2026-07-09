package com.healthupgrades.reminder.adapter.out.persistence;

import com.healthupgrades.reminder.domain.model.Reminder; // managed entity
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository backing {@link ReminderRepositoryAdapter}; package-private internal detail.
 */
interface ReminderJpaRepository extends JpaRepository<Reminder, UUID> {
    List<Reminder> findByUpgradeId(UUID upgradeId); // derived query
    List<Reminder> findByEnabledTrue(); // derived query: enabled reminders for dispatch
}
