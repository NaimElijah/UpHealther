package com.healthupgrades.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.reminder.domain.model.ReminderDays;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers the dispatch sweep's read of every user's enabled reminders, which no request can reach. */
@ExtendWith(MockitoExtension.class)
class ReminderSweepsTest {

    @Mock
    ReminderRepositoryPort repository;

    @InjectMocks
    ReminderSweeps sweeps;

    @Test
    void
            GivenRemindersAcrossUsers_WhenTheDispatcherAsksForAMinutesWork_ThenTheRepositorysAnswerForThatMinuteIsReturned() {
        // Moved from ReminderServiceTest with the method. Deliberately not user-scoped: the scheduler
        // sweeps every user's reminders, and filters by enabled and by minute rather than by owner.
        List<Reminder> enabled =
                List.of(Reminder.create(UUID.randomUUID(), LocalTime.of(9, 0), ReminderDays.of(List.of("MON")), true));
        when(repository.findEnabledWithinMinute(LocalTime.of(9, 0))).thenReturn(enabled);

        assertThat(sweeps.findEnabledWithinMinute(LocalTime.of(9, 0))).isEqualTo(enabled);
    }
}
