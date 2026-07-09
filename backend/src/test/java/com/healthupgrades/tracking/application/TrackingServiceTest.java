package com.healthupgrades.tracking.application;

import com.healthupgrades.common.domain.event.DomainEventPublisher;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.tracking.adapter.in.web.ProgressDto;
import com.healthupgrades.tracking.adapter.in.web.ProgressRequest;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.service.ProgressEvaluationService;
import com.healthupgrades.tracking.domain.service.StreakCalculator;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingType;
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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

    @InjectMocks TrackingService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void recordProgress_setsCompletedFromEvaluation_whenConfigExists() {
        // The client did not supply `completed`; a NUMERIC config exists and the evaluator says "met".
        ProgressRequest req = new ProgressRequest(LocalDate.now(), null, 2.5, "liters", null, null);
        TrackingConfig config = TrackingConfig.builder()
                .upgradeId(upgradeId).trackingType(TrackingType.NUMERIC).targetNumericValue(2.0).build();

        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.of(config));
        when(evaluationService.isSuccessful(any(), any())).thenReturn(true);
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any())).thenReturn(3);

        ProgressDto dto = service.recordProgress(userId, upgradeId, req);

        assertThat(dto.completed()).isTrue();
        verify(evaluationService).isSuccessful(any(), any());
    }

    @Test
    void recordProgress_duplicateDate_throwsAndDoesNotSave() {
        ProgressRequest req = new ProgressRequest(LocalDate.now(), true, null, null, null, null);
        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.recordProgress(userId, upgradeId, req))
                .isInstanceOf(DuplicateProgressException.class);

        verify(progressRepository, never()).save(any());
    }
}
