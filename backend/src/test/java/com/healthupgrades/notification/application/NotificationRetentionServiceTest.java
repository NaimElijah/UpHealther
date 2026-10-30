package com.healthupgrades.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers which notifications the nightly retention job deletes, and how it pages through them (NFR-60). */
@ExtendWith(MockitoExtension.class)
class NotificationRetentionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T03:45:00Z"), ZoneOffset.UTC);

    @Mock
    NotificationRepositoryPort repository;

    private NotificationRetentionService retention;

    @BeforeEach
    void setUp() {
        retention = new NotificationRetentionService(repository, CLOCK);
    }

    @Test
    void GivenTheClock_WhenExpiredNotificationsAreDeleted_ThenTheCutoffIsExactlyNinetyDaysEarlier() {
        // The repository deletes strictly before the cutoff, so a notification created exactly ninety days
        // ago is kept, and one created a moment earlier goes.
        when(repository.deleteCreatedBefore(any(), anyInt())).thenReturn(0);

        retention.deleteExpired();

        verify(repository)
                .deleteCreatedBefore(LocalDateTime.of(2026, 8, 1, 3, 45), NotificationRetentionService.BATCH_SIZE);
    }

    @Test
    void GivenNothingOldEnough_WhenExpiredNotificationsAreDeleted_ThenOneEmptyBatchEndsTheRun() {
        when(repository.deleteCreatedBefore(any(), anyInt())).thenReturn(0);

        assertThat(retention.deleteExpired()).isZero();

        verify(repository, times(1)).deleteCreatedBefore(any(), anyInt());
    }

    @Test
    void
            GivenMoreThanABatchOfOldNotifications_WhenExpiredNotificationsAreDeleted_ThenBatchesRunUntilOneComesBackShort() {
        int full = NotificationRetentionService.BATCH_SIZE;
        // The trailing 0 is for a loop that ran on past a short batch: it fails times(3) rather than
        // looping on the repeated last value until the build times out.
        when(repository.deleteCreatedBefore(any(), anyInt())).thenReturn(full, full, 7, 0);

        assertThat(retention.deleteExpired()).isEqualTo(2 * full + 7);

        verify(repository, times(3)).deleteCreatedBefore(any(), anyInt());
    }

    @Test
    void
            GivenABacklogLargerThanOneRunTakes_WhenExpiredNotificationsAreDeleted_ThenTheRunStopsAtTheCapAndTheNextContinues() {
        // The job shares the scheduler's one thread with the minute-by-minute reminder sweep, so a run gives
        // it back after a bounded number of batches, however much is left, and the next night goes on.
        int full = NotificationRetentionService.BATCH_SIZE;
        int cap = NotificationRetentionService.MAX_BATCHES_PER_RUN;
        Integer[] restOfTheFirstRunThenTheSecond = new Integer[cap];
        Arrays.fill(restOfTheFirstRunThenTheSecond, full);
        restOfTheFirstRunThenTheSecond[cap - 1] = 7;
        when(repository.deleteCreatedBefore(any(), anyInt())).thenReturn(full, restOfTheFirstRunThenTheSecond);

        assertThat(retention.deleteExpired()).as("the first run").isEqualTo(cap * full);
        verify(repository, times(cap)).deleteCreatedBefore(any(), anyInt());

        assertThat(retention.deleteExpired()).as("the next run").isEqualTo(7);
        verify(repository, times(cap + 1)).deleteCreatedBefore(any(), anyInt());
    }

    @Test
    void GivenExactlyOneFullBatch_WhenExpiredNotificationsAreDeleted_ThenAnEmptyBatchConfirmsTheEnd() {
        // A full batch cannot tell "that was all" from "there is more", so one more statement asks.
        int full = NotificationRetentionService.BATCH_SIZE;
        when(repository.deleteCreatedBefore(any(), anyInt())).thenReturn(full, 0);

        assertThat(retention.deleteExpired()).isEqualTo(full);

        verify(repository, times(2)).deleteCreatedBefore(any(), anyInt());
    }
}
