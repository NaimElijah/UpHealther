package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure domain service that computes habit streaks from a list of progress entries.
 *
 * <p>Framework-free and stateless: the application layer wires it as a Spring bean, keeping the
 * framework out of the domain.
 */
public class StreakCalculator {

    /**
     * Calculates the current consecutive-day completion streak. Counts back from today; if nothing was
     * completed today yet, it counts back from yesterday so the streak is not prematurely broken.
     */
    public int calculateCurrentStreak(List<ProgressEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0; // no data -> no streak

        // Collect the distinct dates the user actually completed the habit.
        Set<LocalDate> completedDates = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.getCompleted()))
                .map(ProgressEntry::getDate)
                .collect(Collectors.toSet());

        if (completedDates.isEmpty()) return 0; // logged entries but none completed

        LocalDate today = LocalDate.now();
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

    /** Calculates the longest run of consecutive completed days across the entire history. */
    public int calculateLongestStreak(List<ProgressEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0; // no data -> no streak

        // Distinct completed dates, ascending, so consecutive runs are adjacent.
        List<LocalDate> sortedDates = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.getCompleted()))
                .map(ProgressEntry::getDate)
                .distinct()
                .sorted()
                .toList();

        if (sortedDates.isEmpty()) return 0; // no completed days

        int maxStreak = 1; // best run seen so far
        int currentStreak = 1; // current run length

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
