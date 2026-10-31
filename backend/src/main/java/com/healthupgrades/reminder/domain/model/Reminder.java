package com.healthupgrades.reminder.domain.model;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import lombok.*;

/**
 * A recurring nudge attached to an upgrade.
 *
 * <p>The aggregate answers whether it is due, rather than exposing its schedule for others to interpret:
 * the day filter is stored as a string that only {@link ReminderDays} reads or writes.
 */
@Entity
@Table(name = "reminders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for general use
@AllArgsConstructor(access = AccessLevel.PRIVATE) // backs the builder only
@Builder
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID upgradeId;

    @Column(nullable = false)
    private LocalTime reminderTime;

    /** Storage form of the day filter; behaviour goes through {@link ReminderDays}, never this string. */
    private String daysOfWeek;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Creates a reminder for an upgrade.
     *
     * @param upgradeId    the upgrade it belongs to, and through which it is owned
     * @param reminderTime the time of day it fires at, in the server's zone
     * @param days         the days it fires on; an empty filter means every day. Not null: use
     *                     {@link ReminderDays#EVERY_DAY} for every day
     * @param enabled      whether it starts switched on
     * @return the new reminder
     * @throws BusinessRuleException if no time is given
     */
    public static Reminder create(UUID upgradeId, LocalTime reminderTime, ReminderDays days, boolean enabled) {
        requireTime(reminderTime);
        return Reminder.builder()
                .upgradeId(upgradeId)
                .reminderTime(reminderTime)
                .daysOfWeek(days.toStorageValue())
                .enabled(enabled)
                .build();
    }

    /**
     * Changes when the reminder fires, leaving its enabled state untouched.
     *
     * @param reminderTime the new time of day, in the server's zone
     * @param days         the new day filter; empty means every day
     * @throws BusinessRuleException if no time is given
     */
    public void reschedule(LocalTime reminderTime, ReminderDays days) {
        requireTime(reminderTime);
        this.reminderTime = reminderTime;
        this.daysOfWeek = days.toStorageValue();
    }

    /** Refuses a reminder with no time, which the column cannot hold and nothing could fire. */
    private static void requireTime(LocalTime reminderTime) {
        if (reminderTime == null) throw new BusinessRuleException("A reminder must have a time");
    }

    /**
     * Turns the reminder on or off without otherwise changing its schedule.
     *
     * @param enabled whether it should fire
     */
    public void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The days this reminder fires on; an empty filter means every day. */
    public ReminderDays days() {
        return ReminderDays.fromStorageValue(daysOfWeek);
    }

    /** Whether the reminder is switched on. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * Whether this reminder should fire at the given moment: it is enabled, the day matches its filter,
     * and the wall-clock hour and minute match its configured time.
     *
     * <p>Compared to the minute, not to the second, because the dispatch job wakes once a minute — an
     * exact-instant comparison would miss almost every reminder.
     *
     * @param day  the day being checked
     * @param time the wall-clock time being checked, in the server's zone
     * @return true if the reminder is due right now
     */
    public boolean isDueAt(DayOfWeek day, LocalTime time) {
        return isEnabled()
                && days().includes(day)
                && reminderTime.getHour() == time.getHour()
                && reminderTime.getMinute() == time.getMinute();
    }

    /**
     * Stamps the creation and update timestamps before the row is first inserted.
     *
     * <p>Reads the JVM's default zone rather than the injected clock: a known deviation from NFR-15, tracked by
     * #51 (ADR-020). {@code ServerTimeArchitectureTest} exempts these callbacks, so it does not catch a new one.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Refreshes the update timestamp before each update, from the JVM's default zone as
     * {@link #onCreate} does.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
