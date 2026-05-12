package com.healthupgrades.tracking.domain;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StreakCalculator {

    public int calculateCurrentStreak(List<ProgressEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0;

        Set<LocalDate> completedDates = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.getCompleted()))
                .map(ProgressEntry::getDate)
                .collect(Collectors.toSet());

        if (completedDates.isEmpty()) return 0;

        LocalDate today = LocalDate.now();
        int streak = 0;
        LocalDate current = today;

        while (completedDates.contains(current)) {
            streak++;
            current = current.minusDays(1);
        }

        if (streak == 0) {
            current = today.minusDays(1);
            while (completedDates.contains(current)) {
                streak++;
                current = current.minusDays(1);
            }
        }

        return streak;
    }

    public int calculateLongestStreak(List<ProgressEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0;

        List<LocalDate> sortedDates = entries.stream()
                .filter(e -> Boolean.TRUE.equals(e.getCompleted()))
                .map(ProgressEntry::getDate)
                .distinct()
                .sorted()
                .toList();

        if (sortedDates.isEmpty()) return 0;

        int maxStreak = 1;
        int currentStreak = 1;

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
