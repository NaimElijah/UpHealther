package com.healthupgrades.upgrade.application;

import com.healthupgrades.common.domain.event.DomainEventPublisher;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @BeforeEach
    void setUp() {
        service = new UpgradeService(repository, schedulingService, eventPublisher);
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
    void update_leavingDifficultyUnchanged_shouldNotConsultTheHardLimit() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.HARD)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, upgradeId, requestWithDifficulty(Difficulty.HARD));

        verify(repository, never()).countByUserIdAndStatusAndDifficulty(any(), any(), any());
    }
}
