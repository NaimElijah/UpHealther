package com.healthupgrades.reminder.domain.model;

import com.healthupgrades.common.domain.exception.BusinessRuleException;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /**
     * Every spelling this type accepts, resolved once. The reminder-dispatch job runs every minute over
     * every enabled reminder, so parsing should not walk (and clone) {@code DayOfWeek.values()} per token.
     */
    private static final Map<String, DayOfWeek> BY_NAME = Arrays.stream(DayOfWeek.values())
            .flatMap(day -> Stream.of(Map.entry(day.name(), day), Map.entry(token(day), day)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private final Set<DayOfWeek> days;

    private ReminderDays(Set<DayOfWeek> days) {
        this.days = days;
    }

    /**
     * Parses day tokens supplied from outside.
     *
     * <p>Accepts both the three-letter form and the full day name, in any case, because the boundary
     * previously accepted whatever it was given and stored it verbatim — a full name was silently stored
     * and then never matched.
     *
     * <p>An unrecognised token is rejected rather than skipped. Skipping is worse than failing here:
     * a filter of {@code ["Mondays","Fridays"]} would parse to nothing, and nothing means
     * <em>every day</em>, so a typo would quietly turn a twice-weekly reminder into a daily one.
     *
     * @param tokens day names; null or empty yields {@link #EVERY_DAY}
     * @throws BusinessRuleException if a token is not a recognisable day
     */
    public static ReminderDays of(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return EVERY_DAY;
        Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
        for (String token : tokens) {
            if (isBlank(token)) continue; // a blank entry in the list is noise, not an instruction
            parsed.add(parse(token).orElseThrow(() ->
                    new BusinessRuleException("Not a day of the week: " + token)));
        }
        return new ReminderDays(parsed);
    }

    /**
     * Reads the persisted form written by {@link #toStorageValue()}.
     *
     * <p>Lenient where {@link #of} is strict: rows written before this type existed may hold tokens it
     * cannot read, and refusing to load them would make a reminder unfetchable rather than merely
     * mis-scheduled. Unreadable tokens are dropped.
     */
    public static ReminderDays fromStorageValue(String csv) {
        if (csv == null || csv.isBlank()) return EVERY_DAY;
        Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
        for (String token : csv.split(SEPARATOR)) {
            parse(token).ifPresent(parsed::add);
        }
        return new ReminderDays(parsed);
    }

    /** The persisted form, or null when there is no day filter to store. */
    public String toStorageValue() {
        if (days.isEmpty()) return null;
        return days.stream().map(ReminderDays::token).collect(Collectors.joining(SEPARATOR));
    }

    /** The day tokens, in calendar order, as published to clients. */
    public List<String> toTokens() {
        return days.stream().map(ReminderDays::token).toList();
    }

    /** Whether the reminder fires on the given day. An empty filter includes every day. */
    public boolean includes(DayOfWeek day) {
        return days.isEmpty() || days.contains(day);
    }

    /** Value equality: two filters are equal when they cover the same set of days. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ReminderDays that && days.equals(that.days);
    }

    /** {@inheritDoc} Consistent with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return days.hashCode();
    }

    /** Diagnostic form: the day tokens, or "every day" when there is no filter. */
    @Override
    public String toString() {
        return days.isEmpty() ? "every day" : String.join(SEPARATOR, toTokens());
    }

    private static boolean isBlank(String token) {
        return token == null || token.isBlank();
    }

    private static Optional<DayOfWeek> parse(String token) {
        if (isBlank(token)) return Optional.empty();
        String normalised = token.trim().toUpperCase(Locale.ROOT);
        return Optional.ofNullable(BY_NAME.get(normalised));
    }

    private static String token(DayOfWeek day) {
        return day.name().substring(0, TOKEN_LENGTH);
    }
}
