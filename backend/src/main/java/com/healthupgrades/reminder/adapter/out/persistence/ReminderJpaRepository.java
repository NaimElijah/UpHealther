package com.healthupgrades.reminder.adapter.out.persistence;

import com.healthupgrades.reminder.domain.model.Reminder; // managed entity
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository backing {@link ReminderRepositoryAdapter}; package-private internal detail.
 */
interface ReminderJpaRepository extends JpaRepository<Reminder, UUID> {
    List<Reminder> findByUpgradeId(UUID upgradeId); // derived query

    /**
     * The enabled reminders set for a time within one minute of the day.
     *
     * <p>Native, so PostgreSQL builds both ends of the minute: its first instant and its last microsecond,
     * the precision of a {@code TIME} column, rather than the start of the next minute, which after 23:59
     * would be 00:00 and match nothing. The minute arrives as two integers, not a time: the driver sends a
     * bound {@code LocalTime} as text carrying the JVM's zone offset, which PostgreSQL then reads as whatever
     * type the expression around it asks for. It reads through the partial index
     * {@code idx_reminders_enabled_time} in V15, whose predicate is {@code enabled}.
     */
    @Query(
            value = "SELECT * FROM reminders WHERE enabled AND reminder_time"
                    + " BETWEEN make_time(:hour, :minute, 0) AND make_time(:hour, :minute, 59.999999)",
            nativeQuery = true)
    List<Reminder> findEnabledWithinMinute(@Param("hour") int hour, @Param("minute") int minute);
}
