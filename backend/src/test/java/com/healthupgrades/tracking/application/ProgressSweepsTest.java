package com.healthupgrades.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers the check-in sweep's read of who logged on a day, across every user. */
@ExtendWith(MockitoExtension.class)
class ProgressSweepsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);

    @Mock
    ProgressEntryRepositoryPort progressRepository;

    @InjectMocks
    ProgressSweeps sweeps;

    @Test
    void GivenUsersWhoLoggedOnADay_WhenTheSweepAsks_ThenEveryOneOfThemIsNamed() {
        Set<UUID> logged = Set.of(UUID.randomUUID(), UUID.randomUUID());
        when(progressRepository.findUserIdsWithEntriesOn(DAY)).thenReturn(logged);

        assertThat(sweeps.findUserIdsWithEntriesOn(DAY)).isEqualTo(logged);
    }
}
