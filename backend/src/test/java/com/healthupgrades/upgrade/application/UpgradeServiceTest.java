package com.healthupgrades.upgrade.application;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePlanned;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeRequest;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.upgrade.domain.service.UpgradeSchedulingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpgradeServiceTest {

    @Mock UpgradeRepositoryPort repository;
    @Mock DomainEventPublisher eventPublisher;

    /** The HARD-limit rule is a pure domain service, not a port — exercise the real one. */
    private final UpgradeSchedulingService schedulingService = new UpgradeSchedulingService();

    private UpgradeService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UpgradeService(repository, schedulingService, eventPublisher, fixedClock);
    }

    private HealthUpgrade upgradeWith(UpgradeStatus status, Difficulty difficulty) {
        return HealthUpgrade.builder()
                .id(upgradeId)
                .userId(userId)
                .title("Cold showers")
                .type(UpgradeType.HABIT)
                .status(status)
                .difficulty(difficulty)
                .build();
    }

    private UpgradeRequest requestWithDifficulty(Difficulty difficulty) {
        return new UpgradeRequest(null, "Cold showers", null, UpgradeType.HABIT,
                difficulty, null, null, null, null);
    }

    @Test
    void update_promotingActiveUpgradeToHard_whenLimitAlreadyReached_shouldThrow() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.MEDIUM)));
        when(repository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.update(userId, upgradeId, requestWithDifficulty(Difficulty.HARD)))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void update_promotingActiveUpgradeToHard_whenUnderLimit_shouldSucceed() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.MEDIUM)));
        when(repository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD))
                .thenReturn(2L);
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthUpgrade saved = service.update(userId, upgradeId, requestWithDifficulty(Difficulty.HARD));

        assertThat(saved.getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    void update_promotingUpgradeThatIsNotActive_shouldNotConsultTheHardLimit() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, upgradeId, requestWithDifficulty(Difficulty.HARD));

        // Only ACTIVE upgrades occupy a HARD slot, so a planned promotion must not pay for the count query.
        verify(repository, never()).countByUserIdAndStatusAndDifficulty(any(), any(), any());
    }

    @Test
    void reschedule_revivingAnAbandonedUpgrade_shouldAnnounceItIsPlannedAgain() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ABANDONED, Difficulty.MEDIUM)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));
        LocalDate newDate = LocalDate.of(2026, 4, 1);

        HealthUpgrade revived = service.reschedule(userId, upgradeId, newDate);

        assertThat(revived.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
        ArgumentCaptor<HealthUpgradePlanned> published = ArgumentCaptor.forClass(HealthUpgradePlanned.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().upgradeId()).isEqualTo(upgradeId);
        assertThat(published.getValue().plannedStartDate()).isEqualTo(newDate);
    }

    @Test
    void reschedule_onlyMovingTheDate_shouldAnnounceNothing() {
        // No status transition happened, so there is nothing for a listener to react to.
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reschedule(userId, upgradeId, LocalDate.of(2026, 4, 1));

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void update_leavingDifficultyUnchanged_shouldNotConsultTheHardLimit() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.HARD)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, upgradeId, requestWithDifficulty(Difficulty.HARD));

        verify(repository, never()).countByUserIdAndStatusAndDifficulty(any(), any(), any());
    }
}
