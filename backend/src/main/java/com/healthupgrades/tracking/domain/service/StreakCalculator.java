package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure domain service that computes habit streaks from a list of progress entries, and the milestones
 * they reach.
 *
 * <p>Framework-free and stateless: the application layer wires it as a Spring bean, keeping the
 * framework out of the domain.
 */
public class StreakCalculator {

    /**
     * A milestone falls on every seventh day of a streak (BR-10). Announcing every consecutive day would
     * put a notification in the user's list once a day per tracked upgrade.
     */
    private static final int MILESTONE_INTERVAL_DAYS = 7;

    /**
     * Calculates the current consecutive-day completion streak. Counts back from {@code today}; if
     * nothing was completed today yet, it counts back from yesterday so the streak is not prematurely
     * broken.
     *
     * <p>{@code today} is a parameter rather than a call to the system clock, which keeps this service
     * pure and lets callers that inject a {@link java.time.Clock} decide what "today" means.
     */
    public int calculateCurrentStreak(List<ProgressEntry> entries, LocalDate today) {
        if (entries == null || entries.isEmpty()) return 0;

        // Collect the distinct dates the user actually completed the habit.
        Set<LocalDate> completedDates = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.getCompleted()))
                .map(ProgressEntry::getDate)
                .collect(Collectors.toSet());

        if (completedDates.isEmpty()) return 0;

        int streak = 0;
        LocalDate current = today;

        // Walk backwards from today while each day was completed.
        while (completedDates.contains(current)) {
            streak++;
            current = current.minusDays(1);
        }

        // If today has not been completed yet, count the run ending yesterday instead.
        if (streak == 0) {
            current = today.minusDays(1);
            while (completedDates.contains(current)) {
                streak++;
                current = current.minusDays(1);
            }
        }

        return streak;
    }

    /**
     * Finds the streak milestone, if any, that logging the entry dated {@code loggedOn} reached (BR-10).
     *
     * <p>An entry reaches a milestone when it carries the current streak to or past a multiple of seven.
     * The streak is measured with and without the entry, not only after it: an entry that leaves the
     * streak where it was — a day that did not count, or one backfilled outside the current run — must
     * not announce the milestone the streak already sits on (#101). A backfill can join two runs and
     * jump past a multiple of seven without landing on it; the highest one crossed is the one reached.
     *
     * @param entries  every entry for the upgrade, the one just logged included
     * @param loggedOn the date of the entry just logged; BR-6 makes it unique among {@code entries}
     * @param today    the day the current streak is counted back from, as for
     *                 {@link #calculateCurrentStreak}
     * @return the milestone's length in days, or empty when the entry reached none
     */
    public OptionalInt milestoneReachedBy(List<ProgressEntry> entries, LocalDate loggedOn, LocalDate today) {
        List<ProgressEntry> withoutIt = entries.stream()
                .filter(e -> !e.getDate().equals(loggedOn))
                .toList();
        int milestonesBefore = calculateCurrentStreak(withoutIt, today) / MILESTONE_INTERVAL_DAYS;
        int milestonesAfter = calculateCurrentStreak(entries, today) / MILESTONE_INTERVAL_DAYS;
        return milestonesAfter > milestonesBefore
                ? OptionalInt.of(milestonesAfter * MILESTONE_INTERVAL_DAYS)
                : OptionalInt.empty();
    }

    /** Calculates the longest run of consecutive completed days across the entire history. */
    public int calculateLongestStreak(List<ProgressEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0;

        // Distinct completed dates, ascending, so consecutive runs are adjacent.
        List<LocalDate> sortedDates = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.getCompleted()))
                .map(ProgressEntry::getDate)
                .distinct()
                .sorted()
                .toList();

        if (sortedDates.isEmpty()) return 0;

        int maxStreak = 1;
        int currentStreak = 1;

        // Extend the run when the next date is exactly one day after the previous, else reset.
        for (int i = 1; i < sortedDates.size(); i++) {
            if (sortedDates.get(i).equals(sortedDates.get(i - 1).plusDays(1))) {
                currentStreak++;
                maxStreak = Math.max(maxStreak, currentStreak);
            } else {
                currentStreak = 1;
            }
        }

        return maxStreak;
    }
}
