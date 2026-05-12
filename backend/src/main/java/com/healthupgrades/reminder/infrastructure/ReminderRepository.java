package com.healthupgrades.reminder.infrastructure;

import com.healthupgrades.reminder.domain.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    List<Reminder> findByUpgradeId(UUID upgradeId);
}
