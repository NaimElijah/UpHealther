package com.healthupgrades.upgrade.domain.model;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Covers the aggregate's state machine and construction invariants: which transitions are legal from
 * each state, and which are refused.
 *
 * <p>This is the executable form of the lifecycle the guides describe, so a transition changed here
 * without changing the documentation shows up as a failure.
 */
class HealthUpgradeTest {

    /** A fixed day to judge date-dependent rules against; the aggregate never reads the clock itself. */
    private static final LocalDate REFERENCE_DAY = LocalDate.of(2026, 3, 15);

    /**
     * Builds an aggregate already in the given state. Status has no setter — it moves only through the
     * transition methods — so tests that need a starting state construct one directly.
     */
    private HealthUpgrade upgradeIn(UpgradeStatus status) {
        return HealthUpgrade.builder()
                .title("Test Upgrade")
                .type(UpgradeType.HABIT)
                .status(status)
                .difficulty(Difficulty.MEDIUM)
                .build();
    }

    private HealthUpgrade upgradeIn(UpgradeStatus status, LocalDate targetEndDate) {
        return HealthUpgrade.builder()
                .title("Test Upgrade")
                .type(UpgradeType.HABIT)
                .status(status)
                .difficulty(Difficulty.MEDIUM)
                .targetEndDate(targetEndDate)
                .build();
    }

    // ---- creation ----

    @Test
    void create_withTheRequiredFields_startsAsAnIdea() {
        HealthUpgrade upgrade = HealthUpgrade.create(UUID.randomUUID(), null, "Cold showers", null,
                UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.IDEA);
        assertThat(upgrade.getTitle()).isEqualTo("Cold showers");
    }

    @Test
    void create_withoutAnOwner_shouldThrow() {
        assertThatThrownBy(() -> HealthUpgrade.create(null, null, "Cold showers", null,
                UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_withBlankTitle_shouldThrow() {
        assertThatThrownBy(() -> HealthUpgrade.create(UUID.randomUUID(), null, "   ", null,
                UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_withoutAType_shouldThrow() {
        assertThatThrownBy(() -> HealthUpgrade.create(UUID.randomUUID(), null, "Cold showers", null,
                null, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- editing details ----

    @Test
    void updateDetails_replacesTheEditableFields() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);

        upgrade.updateDetails(null, "New title", "New description", UpgradeType.EXPERIMENT,
                REFERENCE_DAY, "Because", "Done when");

        assertThat(upgrade.getTitle()).isEqualTo("New title");
        assertThat(upgrade.getType()).isEqualTo(UpgradeType.EXPERIMENT);
        assertThat(upgrade.getTargetEndDate()).isEqualTo(REFERENCE_DAY);
    }

    @Test
    void updateDetails_leavesTheLifecycleAlone() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);

        upgrade.updateDetails(null, "New title", null, UpgradeType.HABIT, null, null, null);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void updateDetails_blankingTheTitle_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);

        assertThatThrownBy(() -> upgrade.updateDetails(null, "  ", null, UpgradeType.HABIT, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- lifecycle transitions ----

    @Test
    void plan_fromIdea_shouldTransitionToPlanned() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        LocalDate startDate = REFERENCE_DAY.plusDays(3);

        upgrade.plan(startDate);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
        assertThat(upgrade.getPlannedStartDate()).isEqualTo(startDate);
    }

    @Test
    void plan_fromActive_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        assertThatThrownBy(() -> upgrade.plan(REFERENCE_DAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void activate_fromPlanned_shouldTransitionToActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PLANNED);

        upgrade.activate(REFERENCE_DAY);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
        assertThat(upgrade.getActualStartDate()).isEqualTo(REFERENCE_DAY);
    }

    @Test
    void activate_fromPaused_shouldTransitionToActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        upgrade.activate(REFERENCE_DAY);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void activate_fromIdea_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        assertThatThrownBy(() -> upgrade.activate(REFERENCE_DAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void pause_fromActive_shouldTransitionToPaused() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.pause();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PAUSED);
    }

    @Test
    void pause_fromIdea_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        assertThatThrownBy(upgrade::pause).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void complete_fromActive_shouldTransitionToCompleted() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.complete();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.COMPLETED);
    }

    @Test
    void complete_fromPaused_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        assertThatThrownBy(upgrade::complete).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void abandon_fromActive_shouldTransitionToAbandoned() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.abandon();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ABANDONED);
    }

    @Test
    void abandon_fromCompleted_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.COMPLETED);
        assertThatThrownBy(upgrade::abandon).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void abandon_fromAbandoned_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ABANDONED);
        assertThatThrownBy(upgrade::abandon).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reschedule_fromCompleted_shouldThrow() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.COMPLETED);
        assertThatThrownBy(() -> upgrade.reschedule(REFERENCE_DAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reschedule_fromAbandoned_shouldTransitionToPlanned() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ABANDONED);
        LocalDate newDate = REFERENCE_DAY.plusDays(7);

        upgrade.reschedule(newDate);

        assertThat(upgrade.getPlannedStartDate()).isEqualTo(newDate);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
    }

    @Test
    void reschedule_fromIdea_shouldKeepIdeaStatus() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        LocalDate newDate = REFERENCE_DAY.plusDays(5);

        upgrade.reschedule(newDate);

        assertThat(upgrade.getPlannedStartDate()).isEqualTo(newDate);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.IDEA);
    }

    @Test
    void reschedule_fromPlanned_shouldKeepPlannedStatus() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PLANNED);
        upgrade.reschedule(REFERENCE_DAY.plusDays(10));
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
    }

    @Test
    void reschedule_fromActive_shouldKeepActiveStatus() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.reschedule(REFERENCE_DAY.plusDays(10));
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void reschedule_fromPaused_shouldKeepPausedStatus() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        upgrade.reschedule(REFERENCE_DAY.plusDays(10));
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PAUSED);
    }

    // ---- derived date questions ----

    @Test
    void isOverdue_whenActiveAndPastTargetDate_shouldReturnTrue() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE, REFERENCE_DAY.minusDays(1));
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isTrue();
    }

    @Test
    void isOverdue_whenActiveAndFutureTargetDate_shouldReturnFalse() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE, REFERENCE_DAY.plusDays(5));
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isFalse();
    }

    @Test
    void isOverdue_whenNotActive_shouldReturnFalse() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED, REFERENCE_DAY.minusDays(30));
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isFalse();
    }

    @Test
    void isOverdue_onTheTargetDateItself_shouldReturnFalse() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE, REFERENCE_DAY);
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isFalse();
    }

    @Test
    void isActiveOn_whenActiveAndDateInRange_shouldReturnTrue() {
        HealthUpgrade upgrade = HealthUpgrade.builder()
                .title("Test Upgrade").type(UpgradeType.HABIT).status(UpgradeStatus.ACTIVE)
                .actualStartDate(REFERENCE_DAY.minusDays(3)).targetEndDate(REFERENCE_DAY.plusDays(3))
                .build();
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isTrue();
    }

    @Test
    void isActiveOn_whenNotActive_shouldReturnFalse() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isFalse();
    }
}
