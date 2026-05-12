package com.healthupgrades.upgrade.domain;

import com.healthupgrades.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class HealthUpgradeTest {

    private HealthUpgrade upgrade;

    @BeforeEach
    void setUp() {
        upgrade = HealthUpgrade.builder()
                .title("Test Upgrade")
                .type(UpgradeType.HABIT)
                .status(UpgradeStatus.DRAFT)
                .difficulty(Difficulty.MEDIUM)
                .build();
    }

    @Test
    void plan_fromDraft_shouldTransitionToPlanned() {
        LocalDate startDate = LocalDate.now().plusDays(3);
        upgrade.plan(startDate);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
        assertThat(upgrade.getPlannedStartDate()).isEqualTo(startDate);
    }

    @Test
    void plan_fromActive_shouldThrow() {
        upgrade.setStatus(UpgradeStatus.ACTIVE);
        assertThatThrownBy(() -> upgrade.plan(LocalDate.now()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void activate_fromPlanned_shouldTransitionToActive() {
        upgrade.setStatus(UpgradeStatus.PLANNED);
        LocalDate start = LocalDate.now();
        upgrade.activate(start);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
        assertThat(upgrade.getActualStartDate()).isEqualTo(start);
    }

    @Test
    void activate_fromPaused_shouldTransitionToActive() {
        upgrade.setStatus(UpgradeStatus.PAUSED);
        upgrade.activate(LocalDate.now());
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void activate_fromDraft_shouldThrow() {
        assertThatThrownBy(() -> upgrade.activate(LocalDate.now()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void pause_fromActive_shouldTransitionToPaused() {
        upgrade.setStatus(UpgradeStatus.ACTIVE);
        upgrade.pause();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PAUSED);
    }

    @Test
    void pause_fromDraft_shouldThrow() {
        assertThatThrownBy(() -> upgrade.pause())
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void complete_fromActive_shouldTransitionToCompleted() {
        upgrade.setStatus(UpgradeStatus.ACTIVE);
        upgrade.complete();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.COMPLETED);
    }

    @Test
    void complete_fromPaused_shouldThrow() {
        upgrade.setStatus(UpgradeStatus.PAUSED);
        assertThatThrownBy(() -> upgrade.complete())
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void abandon_fromActive_shouldTransitionToAbandoned() {
        upgrade.setStatus(UpgradeStatus.ACTIVE);
        upgrade.abandon();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ABANDONED);
    }

    @Test
    void abandon_fromCompleted_shouldThrow() {
        upgrade.setStatus(UpgradeStatus.COMPLETED);
        assertThatThrownBy(() -> upgrade.abandon())
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void abandon_fromAbandoned_shouldThrow() {
        upgrade.setStatus(UpgradeStatus.ABANDONED);
        assertThatThrownBy(() -> upgrade.abandon())
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reschedule_shouldUpdatePlannedStartDate() {
        LocalDate newDate = LocalDate.now().plusDays(5);
        upgrade.reschedule(newDate);
        assertThat(upgrade.getPlannedStartDate()).isEqualTo(newDate);
    }

    @Test
    void reschedule_fromCompleted_shouldThrow() {
        upgrade.setStatus(UpgradeStatus.COMPLETED);
        assertThatThrownBy(() -> upgrade.reschedule(LocalDate.now()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void isOverdue_whenActiveAndPastTargetDate_shouldReturnTrue() {
        upgrade.setStatus(UpgradeStatus.ACTIVE);
        upgrade.setTargetEndDate(LocalDate.now().minusDays(1));
        assertThat(upgrade.isOverdue()).isTrue();
    }

    @Test
    void isOverdue_whenActiveAndFutureTargetDate_shouldReturnFalse() {
        upgrade.setStatus(UpgradeStatus.ACTIVE);
        upgrade.setTargetEndDate(LocalDate.now().plusDays(5));
        assertThat(upgrade.isOverdue()).isFalse();
    }

    @Test
    void isActiveOn_whenActiveAndDateInRange_shouldReturnTrue() {
        upgrade.setStatus(UpgradeStatus.ACTIVE);
        upgrade.setActualStartDate(LocalDate.now().minusDays(3));
        upgrade.setTargetEndDate(LocalDate.now().plusDays(3));
        assertThat(upgrade.isActiveOn(LocalDate.now())).isTrue();
    }

    @Test
    void isActiveOn_whenNotActive_shouldReturnFalse() {
        upgrade.setStatus(UpgradeStatus.PAUSED);
        assertThat(upgrade.isActiveOn(LocalDate.now())).isFalse();
    }
}
