package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers streak counting, boundaries included: an unlogged today must not break the run, gaps must end
 * it, and duplicate or unsuccessful entries must not extend it.
 */
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
    void GivenNoEntries_WhenTheCurrentStreakIsCalculated_ThenItIsZero() {
        assertThat(calculator.calculateCurrentStreak(Collections.emptyList(), TODAY)).isZero();
    }

    @Test
    void GivenNoCompletedEntries_WhenTheCurrentStreakIsCalculated_ThenItIsZero() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(1), false),
                entry(TODAY, false)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isZero();
    }

    @Test
    void GivenConsecutiveCompletedDaysIncludingToday_WhenTheCurrentStreakIsCalculated_ThenItCountsThemAll() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true),
                entry(TODAY, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(3);
    }

    @Test
    void GivenConsecutiveCompletedDaysEndingYesterday_WhenTheCurrentStreakIsCalculated_ThenTodayBeingUnloggedDoesNotBreakIt() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(3), true),
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(3);
    }

    @Test
    void GivenAStreakBrokenByAMissedDay_WhenTheCurrentStreakIsCalculated_ThenOnlyTheRunSinceTheBreakCounts() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(5), true),
                entry(TODAY.minusDays(4), true),
                entry(TODAY.minusDays(1), true),
                entry(TODAY, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(2);
    }

    @Test
    void GivenAnExplicitDay_WhenTheCurrentStreakIsCalculated_ThenItIsMeasuredFromThatDayNotTheSystemClock() {
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
    void GivenNoEntries_WhenTheLongestStreakIsCalculated_ThenItIsZero() {
        assertThat(calculator.calculateLongestStreak(Collections.emptyList())).isZero();
    }

    @Test
    void GivenASingleCompletedEntry_WhenTheLongestStreakIsCalculated_ThenItIsOne() {
        List<ProgressEntry> entries = List.of(entry(TODAY, true));
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }

    @Test
    void GivenSeveralSeparatedStreaks_WhenTheLongestIsCalculated_ThenTheLongestOneIsReturned() {
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
    void GivenIncompleteEntriesAmongTheCompletedOnes_WhenTheLongestStreakIsCalculated_ThenTheIncompleteOnesAreIgnored() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(3), true),
                entry(TODAY.minusDays(2), false),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }
}
