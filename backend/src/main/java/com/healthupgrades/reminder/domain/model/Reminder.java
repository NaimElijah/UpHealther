package com.healthupgrades.reminder.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A recurring nudge attached to an upgrade.
 *
 * <p>The aggregate answers whether it is due, rather than exposing its schedule for others to interpret:
 * the day list used to be a raw CSV column that the notification scheduler re-parsed with its own copy
 * of the format.
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

    private LocalTime reminderTime;

    /** Storage form of the day filter; behaviour goes through {@link ReminderDays}, never this string. */
    private String daysOfWeek;

    private Boolean enabled;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Creates a reminder for an upgrade.
     *
     * @param upgradeId    the upgrade it belongs to, and through which it is owned
     * @param reminderTime the local time it fires at
     * @param days         the days it fires on; an empty filter means every day
     * @param enabled      whether it starts switched on
     * @return the new reminder
     */
    public static Reminder create(UUID upgradeId, LocalTime reminderTime, ReminderDays days, boolean enabled) {
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
     * @param reminderTime the new local time
     * @param days         the new day filter; empty means every day
     */
    public void reschedule(LocalTime reminderTime, ReminderDays days) {
        this.reminderTime = reminderTime;
        this.daysOfWeek = days.toStorageValue();
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
     * @param time the wall-clock time being checked
     * @return true if the reminder is due right now
     */
    public boolean isDueAt(DayOfWeek day, LocalTime time) {
        return isEnabled()
                && reminderTime != null
                && days().includes(day)
                && reminderTime.getHour() == time.getHour()
                && reminderTime.getMinute() == time.getMinute();
    }

    /** Stamps the creation and update timestamps before the row is first inserted. */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** Refreshes the update timestamp before each update. */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
