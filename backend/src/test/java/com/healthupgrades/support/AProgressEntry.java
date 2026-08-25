package com.healthupgrades.support;

import com.healthupgrades.tracking.domain.model.ProgressEntry;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Progress entries for tests.
 *
 * <p>An entry carries every value field and fills in the one its upgrade's {@code TrackingType} calls
 * for, so these factories are named for the field they set — pairing each with the matching
 * {@link ATrackingConfig} factory is what makes a scoring test readable.
 */
public final class AProgressEntry {

    private AProgressEntry() {
    }

    /** An entry for the given upgrade and day, with no value set. */
    public static ProgressEntry.ProgressEntryBuilder on(UUID upgradeId, UUID userId, LocalDate date) {
        return ProgressEntry.builder()
                .id(UUID.randomUUID())
                .upgradeId(upgradeId)
                .userId(userId)
                .date(date);
    }

    /** A boolean entry — also what streaks are counted from. */
    public static ProgressEntry completedOn(UUID upgradeId, UUID userId, LocalDate date, boolean completed) {
        return on(upgradeId, userId, date).completed(completed).build();
    }

    /** A numeric entry, in the unit it was logged in. */
    public static ProgressEntry numericOn(UUID upgradeId, UUID userId, LocalDate date,
                                          double value, String unit) {
        return on(upgradeId, userId, date).numericValue(value).unit(unit).build();
    }

    /** A rating entry, one to five. */
    public static ProgressEntry ratedOn(UUID upgradeId, UUID userId, LocalDate date, int rating) {
        return on(upgradeId, userId, date).rating(rating).build();
    }

    /** A text entry, where the note is what is scored. */
    public static ProgressEntry notedOn(UUID upgradeId, UUID userId, LocalDate date, String note) {
        return on(upgradeId, userId, date).note(note).build();
    }
}
