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
    void GivenTheRequiredFields_WhenAnUpgradeIsCreated_ThenItStartsAsAnIdea() {
        HealthUpgrade upgrade = HealthUpgrade.create(UUID.randomUUID(), null, "Cold showers", null,
                UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.IDEA);
        assertThat(upgrade.getTitle()).isEqualTo("Cold showers");
    }

    @Test
    void GivenNoOwner_WhenAnUpgradeIsCreated_ThenItIsRejected() {
        assertThatThrownBy(() -> HealthUpgrade.create(null, null, "Cold showers", null,
                UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenABlankTitle_WhenAnUpgradeIsCreated_ThenItIsRejected() {
        assertThatThrownBy(() -> HealthUpgrade.create(UUID.randomUUID(), null, "   ", null,
                UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenNoType_WhenAnUpgradeIsCreated_ThenItIsRejected() {
        assertThatThrownBy(() -> HealthUpgrade.create(UUID.randomUUID(), null, "Cold showers", null,
                null, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- editing details ----

    @Test
    void GivenAnUpgrade_WhenItsDetailsAreUpdated_ThenTheEditableFieldsAreReplaced() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);

        upgrade.updateDetails(null, "New title", "New description", UpgradeType.EXPERIMENT,
                REFERENCE_DAY, "Because", "Done when");

        assertThat(upgrade.getTitle()).isEqualTo("New title");
        assertThat(upgrade.getType()).isEqualTo(UpgradeType.EXPERIMENT);
        assertThat(upgrade.getTargetEndDate()).isEqualTo(REFERENCE_DAY);
    }

    @Test
    void GivenAnUpgradeInAnyState_WhenItsDetailsAreUpdated_ThenItsLifecycleIsUntouched() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);

        upgrade.updateDetails(null, "New title", null, UpgradeType.HABIT, null, null, null);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void GivenABlankTitle_WhenDetailsAreUpdated_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);

        assertThatThrownBy(() -> upgrade.updateDetails(null, "  ", null, UpgradeType.HABIT, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- lifecycle transitions ----

    @Test
    void GivenAnIdea_WhenItIsPlanned_ThenItBecomesPlanned() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        LocalDate startDate = REFERENCE_DAY.plusDays(3);

        upgrade.plan(startDate);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
        assertThat(upgrade.getPlannedStartDate()).isEqualTo(startDate);
    }

    @Test
    void GivenAnActiveUpgrade_WhenItIsPlanned_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        assertThatThrownBy(() -> upgrade.plan(REFERENCE_DAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenAPlannedUpgrade_WhenItIsActivated_ThenItBecomesActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PLANNED);

        upgrade.activate(REFERENCE_DAY);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
        assertThat(upgrade.getActualStartDate()).isEqualTo(REFERENCE_DAY);
    }

    @Test
    void GivenAPausedUpgrade_WhenItIsActivated_ThenItBecomesActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        upgrade.activate(REFERENCE_DAY);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void GivenAnIdea_WhenItIsActivated_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        assertThatThrownBy(() -> upgrade.activate(REFERENCE_DAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenAnActiveUpgrade_WhenItIsPaused_ThenItBecomesPaused() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.pause();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PAUSED);
    }

    @Test
    void GivenAnIdea_WhenItIsPaused_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        assertThatThrownBy(upgrade::pause).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenAnActiveUpgrade_WhenItIsCompleted_ThenItBecomesCompleted() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.complete();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.COMPLETED);
    }

    @Test
    void GivenAPausedUpgrade_WhenItIsCompleted_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        assertThatThrownBy(upgrade::complete).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenAnActiveUpgrade_WhenItIsAbandoned_ThenItBecomesAbandoned() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.abandon();
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ABANDONED);
    }

    @Test
    void GivenACompletedUpgrade_WhenItIsAbandoned_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.COMPLETED);
        assertThatThrownBy(upgrade::abandon).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenAnAbandonedUpgrade_WhenItIsAbandonedAgain_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ABANDONED);
        assertThatThrownBy(upgrade::abandon).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenACompletedUpgrade_WhenItIsRescheduled_ThenItIsRejected() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.COMPLETED);
        assertThatThrownBy(() -> upgrade.reschedule(REFERENCE_DAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenAnAbandonedUpgrade_WhenItIsRescheduled_ThenItBecomesPlannedAgain() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ABANDONED);
        LocalDate newDate = REFERENCE_DAY.plusDays(7);

        upgrade.reschedule(newDate);

        assertThat(upgrade.getPlannedStartDate()).isEqualTo(newDate);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
    }

    @Test
    void GivenAnIdea_WhenItIsRescheduled_ThenItStaysAnIdea() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.IDEA);
        LocalDate newDate = REFERENCE_DAY.plusDays(5);

        upgrade.reschedule(newDate);

        assertThat(upgrade.getPlannedStartDate()).isEqualTo(newDate);
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.IDEA);
    }

    @Test
    void GivenAPlannedUpgrade_WhenItIsRescheduled_ThenItStaysPlanned() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PLANNED);
        upgrade.reschedule(REFERENCE_DAY.plusDays(10));
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
    }

    @Test
    void GivenAnActiveUpgrade_WhenItIsRescheduled_ThenItStaysActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        upgrade.reschedule(REFERENCE_DAY.plusDays(10));
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void GivenAPausedUpgrade_WhenItIsRescheduled_ThenItStaysPaused() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        upgrade.reschedule(REFERENCE_DAY.plusDays(10));
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PAUSED);
    }

    // ---- derived date questions ----

    @Test
    void GivenAnActiveUpgradePastItsTargetDate_WhenOverdueIsChecked_ThenItIsOverdue() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE, REFERENCE_DAY.minusDays(1));
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isTrue();
    }

    @Test
    void GivenAnActiveUpgradeBeforeItsTargetDate_WhenOverdueIsChecked_ThenItIsNotOverdue() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE, REFERENCE_DAY.plusDays(5));
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isFalse();
    }

    @Test
    void GivenAnUpgradeThatIsNotActive_WhenOverdueIsChecked_ThenItIsNotOverdue() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED, REFERENCE_DAY.minusDays(30));
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isFalse();
    }

    @Test
    void GivenAnActiveUpgradeOnItsTargetDate_WhenOverdueIsChecked_ThenItIsNotOverdue() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE, REFERENCE_DAY);
        assertThat(upgrade.isOverdue(REFERENCE_DAY)).isFalse();
    }

    @Test
    void GivenAnActiveUpgradeSpanningTheDate_WhenActivityOnThatDateIsChecked_ThenItIsActive() {
        HealthUpgrade upgrade = HealthUpgrade.builder()
                .title("Test Upgrade").type(UpgradeType.HABIT).status(UpgradeStatus.ACTIVE)
                .actualStartDate(REFERENCE_DAY.minusDays(3)).targetEndDate(REFERENCE_DAY.plusDays(3))
                .build();
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isTrue();
    }

    @Test
    void GivenAnUpgradeThatIsNotActive_WhenActivityOnADateIsChecked_ThenItIsNotActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isFalse();
    }
}
