package com.healthupgrades.tracking.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreakCalculatorTest {

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
        assertThat(calculator.calculateCurrentStreak(Collections.emptyList())).isZero();
    }

    @Test
    void currentStreak_noCompletedEntries_returnsZero() {
        List<ProgressEntry> entries = List.of(
                entry(LocalDate.now().minusDays(1), false),
                entry(LocalDate.now(), false)
        );
        assertThat(calculator.calculateCurrentStreak(entries)).isZero();
    }

    @Test
    void currentStreak_consecutiveDaysIncludingToday_returnsCorrectStreak() {
        LocalDate today = LocalDate.now();
        List<ProgressEntry> entries = List.of(
                entry(today.minusDays(2), true),
                entry(today.minusDays(1), true),
                entry(today, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries)).isEqualTo(3);
    }

    @Test
    void currentStreak_consecutiveDaysExcludingToday_returnsCorrectStreak() {
        LocalDate today = LocalDate.now();
        List<ProgressEntry> entries = List.of(
                entry(today.minusDays(3), true),
                entry(today.minusDays(2), true),
                entry(today.minusDays(1), true)
        );
        assertThat(calculator.calculateCurrentStreak(entries)).isEqualTo(3);
    }

    @Test
    void currentStreak_brokenStreak_returnsCurrentOnly() {
        LocalDate today = LocalDate.now();
        List<ProgressEntry> entries = List.of(
                entry(today.minusDays(5), true),
                entry(today.minusDays(4), true),
                entry(today.minusDays(1), true),
                entry(today, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries)).isEqualTo(2);
    }

    @Test
    void longestStreak_emptyList_returnsZero() {
        assertThat(calculator.calculateLongestStreak(Collections.emptyList())).isZero();
    }

    @Test
    void longestStreak_singleEntry_returnsOne() {
        List<ProgressEntry> entries = List.of(entry(LocalDate.now(), true));
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }

    @Test
    void longestStreak_multipleSeparatedStreaks_returnsLongest() {
        LocalDate today = LocalDate.now();
        List<ProgressEntry> entries = List.of(
                entry(today.minusDays(10), true),
                entry(today.minusDays(9), true),
                entry(today.minusDays(8), true),
                entry(today.minusDays(5), true),
                entry(today.minusDays(4), true)
        );
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(3);
    }

    @Test
    void longestStreak_ignoresNonCompletedEntries() {
        LocalDate today = LocalDate.now();
        List<ProgressEntry> entries = List.of(
                entry(today.minusDays(3), true),
                entry(today.minusDays(2), false),
                entry(today.minusDays(1), true)
        );
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }
}
