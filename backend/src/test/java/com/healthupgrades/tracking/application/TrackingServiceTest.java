package com.healthupgrades.tracking.application;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.tracking.application.port.in.ProgressEntryDetails;

import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.service.ProgressEvaluationService;
import com.healthupgrades.tracking.domain.service.StreakCalculator;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingType;
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    @Mock TrackingConfigRepositoryPort configRepository;
    @Mock ProgressEntryRepositoryPort progressRepository;
    @Mock UpgradeQuery upgradeQuery;
    @Mock StreakCalculator streakCalculator;
    @Mock ProgressEvaluationService evaluationService;
    @Mock DomainEventPublisher eventPublisher;

    /** Fixed so "today" is decided here rather than by whenever the suite happens to run. */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC);
    private final LocalDate today = LocalDate.of(2026, 3, 15);

    private TrackingService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrackingService(configRepository, progressRepository, upgradeQuery,
                streakCalculator, evaluationService, eventPublisher, fixedClock);
    }

    @Test
    void recordProgress_setsCompletedFromEvaluation_whenConfigExists() {
        // The client did not supply `completed`; a NUMERIC config exists and the evaluator says "met".
        ProgressEntryDetails req = new ProgressEntryDetails(today, null, 2.5, "liters", null, null);
        TrackingConfig config = TrackingConfig.builder()
                .upgradeId(upgradeId).trackingType(TrackingType.NUMERIC).targetNumericValue(2.0).build();

        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.of(config));
        when(evaluationService.isSuccessful(any(), any())).thenReturn(true);
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any(), any())).thenReturn(3);

        ProgressEntry saved = service.recordProgress(userId, upgradeId, req);

        assertThat(saved.getCompleted()).isTrue();
        verify(evaluationService).isSuccessful(any(), any());
    }

    @Test
    void recordProgress_duplicateDate_throwsAndDoesNotSave() {
        ProgressEntryDetails req = new ProgressEntryDetails(today, true, null, null, null, null);
        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.recordProgress(userId, upgradeId, req))
                .isInstanceOf(DuplicateProgressException.class);

        verify(progressRepository, never()).save(any());
    }
}
