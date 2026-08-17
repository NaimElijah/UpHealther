package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreakCalculatorTest {

    /** A fixed reference day: the calculator is told what "today" is, so nothing here reads the clock. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private StreakCalculator calculator;
    private final UUID upgradeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        calculator = new StreakCalculator();
    }

    private ProgressEntry entry(LocalDate date, boolean completed) {
        return ProgressEntry.builder()
                .id(UUID.randomUUID())
                .upgradeId(upgradeId)
                .userId(userId)
                .date(date)
                .completed(completed)
                .build();
    }

    @Test
    void currentStreak_emptyList_returnsZero() {
        assertThat(calculator.calculateCurrentStreak(Collections.emptyList(), TODAY)).isZero();
    }

    @Test
    void currentStreak_noCompletedEntries_returnsZero() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(1), false),
                entry(TODAY, false)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isZero();
    }

    @Test
    void currentStreak_consecutiveDaysIncludingToday_returnsCorrectStreak() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true),
                entry(TODAY, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(3);
    }

    @Test
    void currentStreak_consecutiveDaysExcludingToday_returnsCorrectStreak() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(3), true),
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(3);
    }

    @Test
    void currentStreak_brokenStreak_returnsCurrentOnly() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(5), true),
                entry(TODAY.minusDays(4), true),
                entry(TODAY.minusDays(1), true),
                entry(TODAY, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(2);
    }

    @Test
    void currentStreak_isMeasuredFromTheGivenDayNotTheSystemClock() {
        // The same entries yield a different answer for a different reference day, which they could not
        // do if the calculator consulted a clock of its own. Both reference days are fixed, so this
        // holds whatever date the suite runs on.
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(2);
        assertThat(calculator.calculateCurrentStreak(entries, TODAY.plusYears(1))).isZero();
    }

    @Test
    void longestStreak_emptyList_returnsZero() {
        assertThat(calculator.calculateLongestStreak(Collections.emptyList())).isZero();
    }

    @Test
    void longestStreak_singleEntry_returnsOne() {
        List<ProgressEntry> entries = List.of(entry(TODAY, true));
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }

    @Test
    void longestStreak_multipleSeparatedStreaks_returnsLongest() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(10), true),
                entry(TODAY.minusDays(9), true),
                entry(TODAY.minusDays(8), true),
                entry(TODAY.minusDays(5), true),
                entry(TODAY.minusDays(4), true)
        );
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(3);
    }

    @Test
    void longestStreak_ignoresNonCompletedEntries() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(3), true),
                entry(TODAY.minusDays(2), false),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }
}
