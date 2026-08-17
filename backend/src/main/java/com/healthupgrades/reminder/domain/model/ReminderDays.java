package com.healthupgrades.reminder.domain.model;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The days of the week a reminder fires on.
 *
 * <p>Exists because the set was previously a bare comma-separated {@code String} whose format was parsed
 * independently in two other layers — the reminder application service and the notification scheduler —
 * so nothing owned the encoding and the two readers could disagree. All parsing and formatting lives
 * here now, and the storage form is an implementation detail of this type.
 *
 * <p>An empty set means <em>every day</em> rather than <em>no days</em>: a reminder with no day filter
 * is the common case and fires daily.
 */
public final class ReminderDays {

    /** How days are written in storage and on the wire, e.g. {@code MON}. */
    private static final int TOKEN_LENGTH = 3;
    private static final String SEPARATOR = ",";

    /** No day filter — the reminder fires every day. */
    public static final ReminderDays EVERY_DAY = new ReminderDays(EnumSet.noneOf(DayOfWeek.class));

    private final Set<DayOfWeek> days;

    private ReminderDays(Set<DayOfWeek> days) {
        this.days = days;
    }

    /**
     * Parses day tokens supplied from outside, ignoring blanks and anything unrecognised.
     *
     * <p>Accepts both the three-letter form and the full day name, in any case, because the boundary
     * previously accepted whatever it was given and stored it verbatim — a full name was silently stored
     * and then never matched.
     *
     * @param tokens day names; null or empty yields {@link #EVERY_DAY}
     */
    public static ReminderDays of(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return EVERY_DAY;
        Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
        for (String token : tokens) {
            parse(token).ifPresent(parsed::add);
        }
        return new ReminderDays(parsed);
    }

    /** Reads the persisted form written by {@link #toStorageValue()}. */
    public static ReminderDays fromStorageValue(String csv) {
        if (csv == null || csv.isBlank()) return EVERY_DAY;
        return of(Arrays.asList(csv.split(SEPARATOR)));
    }

    /** The persisted form, or null when there is no day filter to store. */
    public String toStorageValue() {
        if (days.isEmpty()) return null;
        return days.stream().map(ReminderDays::token).reduce((a, b) -> a + SEPARATOR + b).orElse(null);
    }

    /** The day tokens, in calendar order, as published to clients. */
    public List<String> toTokens() {
        return days.stream().map(ReminderDays::token).toList();
    }

    /** Whether the reminder fires on the given day. An empty filter includes every day. */
    public boolean includes(DayOfWeek day) {
        return days.isEmpty() || days.contains(day);
    }

    private static Optional<DayOfWeek> parse(String token) {
        if (token == null) return Optional.empty();
        String normalised = token.trim().toUpperCase(Locale.ROOT);
        if (normalised.isEmpty()) return Optional.empty();
        return Arrays.stream(DayOfWeek.values())
                .filter(d -> d.name().equals(normalised) || token(d).equals(normalised))
                .findFirst();
    }

    private static String token(DayOfWeek day) {
        return day.name().substring(0, TOKEN_LENGTH);
    }
}
