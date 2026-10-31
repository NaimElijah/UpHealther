package com.healthupgrades.tracking.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The exact bound on a progress entry's date (FR-18): the server's tomorrow is the last day accepted.
 *
 * <p>On a fixed clock, because the slice that shows the bound answering 400 runs on the real one and so
 * can only use a date far past it.
 */
class NoLaterThanTomorrowTest {

    /** Late evening in the server's zone, so a zone ahead of it is already on the next day. */
    private static final Instant NOW = Instant.parse("2026-06-24T22:30:00Z");

    private static final LocalDate SERVER_TODAY = LocalDate.of(2026, 6, 24);

    private final NoLaterThanTomorrow.Validator validator =
            new NoLaterThanTomorrow.Validator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void GivenTheServersTomorrow_WhenTheDateIsChecked_ThenItIsAccepted() {
        // A user fourteen hours ahead of UTC is already logging this day (#100).
        assertThat(validator.isValid(SERVER_TODAY.plusDays(1), null)).isTrue();
    }

    @Test
    void GivenTheDayAfterTheServersTomorrow_WhenTheDateIsChecked_ThenItIsRefused() {
        assertThat(validator.isValid(SERVER_TODAY.plusDays(2), null)).isFalse();
    }

    @Test
    void GivenTodayOrAPastDay_WhenTheDateIsChecked_ThenItIsAccepted() {
        assertThat(validator.isValid(SERVER_TODAY, null)).isTrue();
        assertThat(validator.isValid(SERVER_TODAY.minusYears(1), null)).isTrue();
    }

    @Test
    void GivenNoDate_WhenItIsChecked_ThenItIsAcceptedForTheUseCaseToTakeToday() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void GivenAClockInAZoneAheadOfTheServer_WhenTheDateIsChecked_ThenTheBoundIsStillTheServersTomorrow() {
        // The day is read in ServerZone, not the clock's own zone, which here is already on 25 June.
        NoLaterThanTomorrow.Validator ahead =
                new NoLaterThanTomorrow.Validator(Clock.fixed(NOW, ZoneOffset.ofHours(14)));

        assertThat(ahead.isValid(SERVER_TODAY.plusDays(2), null)).isFalse();
    }
}
