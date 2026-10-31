package com.healthupgrades.upgrade.domain.model;

import static org.assertj.core.api.Assertions.*;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

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
        HealthUpgrade upgrade = HealthUpgrade.create(
                UUID.randomUUID(),
                null,
                "Cold showers",
                null,
                UpgradeType.HABIT,
                Difficulty.MEDIUM,
                null,
                null,
                null,
                null);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.IDEA);
        assertThat(upgrade.getTitle()).isEqualTo("Cold showers");
    }

    @Test
    void GivenNoOwner_WhenAnUpgradeIsCreated_ThenItIsRejected() {
        assertThatThrownBy(() -> HealthUpgrade.create(
                        null, null, "Cold showers", null, UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenABlankTitle_WhenAnUpgradeIsCreated_ThenItIsRejected() {
        assertThatThrownBy(() -> HealthUpgrade.create(
                        UUID.randomUUID(),
                        null,
                        "   ",
                        null,
                        UpgradeType.HABIT,
                        Difficulty.MEDIUM,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenNoType_WhenAnUpgradeIsCreated_ThenItIsRejected() {
        assertThatThrownBy(() -> HealthUpgrade.create(
                        UUID.randomUUID(), null, "Cold showers", null, null, Difficulty.MEDIUM, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- editing details ----

    @Test
    void GivenAnUpgrade_WhenEveryEditableFieldIsReplaced_ThenEachHoldsItsNewValue() {
        // FR-13 names seven editable fields; each gets a value no other one has, so a field left
        // unassigned or assigned into its neighbour shows here.
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.ACTIVE);
        UUID areaId = UUID.randomUUID();

        upgrade.updateDetails(
                areaId, "New title", "New description", UpgradeType.GOAL, REFERENCE_DAY, "Because", "Done when");

        assertThat(upgrade.getAreaId()).isEqualTo(areaId);
        assertThat(upgrade.getTitle()).isEqualTo("New title");
        assertThat(upgrade.getDescription()).isEqualTo("New description");
        assertThat(upgrade.getType()).isEqualTo(UpgradeType.GOAL);
        assertThat(upgrade.getTargetEndDate()).isEqualTo(REFERENCE_DAY);
        assertThat(upgrade.getMotivation()).isEqualTo("Because");
        assertThat(upgrade.getSuccessCriteria()).isEqualTo("Done when");
    }

    @ParameterizedTest
    @EnumSource(UpgradeStatus.class)
    void GivenAnUpgradeInEachState_WhenItsDetailsAreUpdated_ThenTheEditLandsAndTheStateIsKept(UpgradeStatus status) {
        // FR-13: editable "at any point in its lifecycle", COMPLETED and ABANDONED included.
        HealthUpgrade upgrade = upgradeIn(status);

        upgrade.updateDetails(null, "New title", null, UpgradeType.HABIT, null, null, null);

        assertThat(upgrade.getTitle()).isEqualTo("New title");
        assertThat(upgrade.getStatus()).isEqualTo(status);
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
    void GivenAPlannedUpgrade_WhenItIsActivated_ThenItBecomesActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PLANNED);

        upgrade.activate(REFERENCE_DAY);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
        assertThat(upgrade.getActualStartDate()).isEqualTo(REFERENCE_DAY);
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

    // ---- BR-2's transition table, every move from every state ----

    /** The outcome column's value for a move BR-2 refuses from the row's state. */
    private static final String REFUSED = "REFUSED";

    /**
     * Applies a move to an upgrade in {@code from} and checks the outcome BR-2 gives for it: the state it
     * ends in, or a refusal that leaves the state where it was.
     *
     * @return the upgrade after the move, for a row that has more than the state to check
     */
    private HealthUpgrade assertTransition(UpgradeStatus from, String outcome, Consumer<HealthUpgrade> move) {
        HealthUpgrade upgrade = upgradeIn(from);

        if (REFUSED.equals(outcome)) {
            assertThat(catchThrowable(() -> move.accept(upgrade)))
                    .as("BR-2 refuses this move from %s", from)
                    .isInstanceOf(BusinessRuleException.class);
            assertThat(upgrade.getStatus())
                    .as("a refused move leaves the state alone")
                    .isEqualTo(from);
        } else {
            move.accept(upgrade);
            assertThat(upgrade.getStatus())
                    .as("BR-2's outcome from %s", from)
                    .isEqualTo(UpgradeStatus.valueOf(outcome));
        }
        return upgrade;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "IDEA, PLANNED",
        "PLANNED, REFUSED",
        "ACTIVE, REFUSED",
        "PAUSED, REFUSED",
        "COMPLETED, REFUSED",
        "ABANDONED, REFUSED"
    })
    void GivenAnUpgradeInEachState_WhenItIsPlanned_ThenOnlyAnIdeaBecomesPlanned(UpgradeStatus from, String outcome) {
        assertTransition(from, outcome, upgrade -> upgrade.plan(REFERENCE_DAY));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "IDEA, REFUSED",
        "PLANNED, ACTIVE",
        "ACTIVE, REFUSED",
        "PAUSED, ACTIVE",
        "COMPLETED, REFUSED",
        "ABANDONED, REFUSED"
    })
    void GivenAnUpgradeInEachState_WhenItIsActivated_ThenOnlyAPlannedOrPausedOneBecomesActive(
            UpgradeStatus from, String outcome) {
        assertTransition(from, outcome, upgrade -> upgrade.activate(REFERENCE_DAY));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(UpgradeStatus.class)
    void GivenAnUpgradeInEachState_WhenAskedWhetherItCanBeActivated_ThenItAnswersAsTheTransitionWould(
            UpgradeStatus status) {
        // D3.2: the service decides on this whether an activation claims a HARD slot, so it must never
        // disagree with the transition it stands in for.
        boolean accepted = catchThrowable(() -> upgradeIn(status).activate(REFERENCE_DAY)) == null;

        assertThat(upgradeIn(status).canActivate()).isEqualTo(accepted);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "IDEA, REFUSED",
        "PLANNED, REFUSED",
        "ACTIVE, PAUSED",
        "PAUSED, REFUSED",
        "COMPLETED, REFUSED",
        "ABANDONED, REFUSED"
    })
    void GivenAnUpgradeInEachState_WhenItIsPaused_ThenOnlyAnActiveOneBecomesPaused(UpgradeStatus from, String outcome) {
        assertTransition(from, outcome, HealthUpgrade::pause);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "IDEA, REFUSED",
        "PLANNED, REFUSED",
        "ACTIVE, COMPLETED",
        "PAUSED, REFUSED",
        "COMPLETED, REFUSED",
        "ABANDONED, REFUSED"
    })
    void GivenAnUpgradeInEachState_WhenItIsCompleted_ThenOnlyAnActiveOneBecomesCompleted(
            UpgradeStatus from, String outcome) {
        assertTransition(from, outcome, HealthUpgrade::complete);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "IDEA, ABANDONED",
        "PLANNED, ABANDONED",
        "ACTIVE, ABANDONED",
        "PAUSED, ABANDONED",
        "COMPLETED, REFUSED",
        "ABANDONED, REFUSED"
    })
    void GivenAnUpgradeInEachState_WhenItIsAbandoned_ThenOnlyANonFinalOneBecomesAbandoned(
            UpgradeStatus from, String outcome) {
        assertTransition(from, outcome, HealthUpgrade::abandon);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "IDEA, IDEA",
        "PLANNED, PLANNED",
        "ACTIVE, ACTIVE",
        "PAUSED, PAUSED",
        "COMPLETED, REFUSED",
        "ABANDONED, PLANNED"
    })
    void GivenAnUpgradeInEachState_WhenItIsRescheduled_ThenOnlyAnAbandonedOneChangesStateAndACompletedOneIsRefused(
            UpgradeStatus from, String outcome) {
        LocalDate newDate = REFERENCE_DAY.plusDays(7);

        HealthUpgrade upgrade = assertTransition(from, outcome, it -> it.reschedule(newDate));

        // BR-2: whatever the state does, every reschedule it allows moves the planned start.
        if (!REFUSED.equals(outcome)) {
            assertThat(upgrade.getPlannedStartDate())
                    .as("BR-2's planned start after rescheduling from %s", from)
                    .isEqualTo(newDate);
        }
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
                .title("Test Upgrade")
                .type(UpgradeType.HABIT)
                .status(UpgradeStatus.ACTIVE)
                .actualStartDate(REFERENCE_DAY.minusDays(3))
                .targetEndDate(REFERENCE_DAY.plusDays(3))
                .build();
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isTrue();
    }

    @Test
    void GivenAnActiveUpgradeThatStartsAfterTheDate_WhenActivityOnThatDateIsChecked_ThenItIsNotActive() {
        HealthUpgrade upgrade = activeBetween(REFERENCE_DAY.plusDays(1), REFERENCE_DAY.plusDays(30));
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isFalse();
    }

    @Test
    void GivenAnActiveUpgradeThatEndedBeforeTheDate_WhenActivityOnThatDateIsChecked_ThenItIsNotActive() {
        HealthUpgrade upgrade = activeBetween(REFERENCE_DAY.minusDays(30), REFERENCE_DAY.minusDays(1));
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isFalse();
    }

    @Test
    void GivenAnActiveUpgradeStartingAndEndingOnTheDate_WhenActivityOnThatDateIsChecked_ThenBothEndsAreInclusive() {
        HealthUpgrade upgrade = activeBetween(REFERENCE_DAY, REFERENCE_DAY);
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isTrue();
    }

    @Test
    void GivenAnActiveUpgradeWithNoStartDate_WhenActivityOnADateIsChecked_ThenTheStartIsOpenEnded() {
        HealthUpgrade upgrade = activeBetween(null, REFERENCE_DAY.plusDays(3));
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isTrue();
    }

    @Test
    void GivenAnActiveUpgradeWithNoTargetDate_WhenActivityOnADateIsChecked_ThenTheEndIsOpenEnded() {
        HealthUpgrade upgrade = activeBetween(REFERENCE_DAY.minusDays(3), null);
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isTrue();
    }

    private HealthUpgrade activeBetween(LocalDate actualStart, LocalDate targetEnd) {
        return HealthUpgrade.builder()
                .title("Test Upgrade")
                .type(UpgradeType.HABIT)
                .status(UpgradeStatus.ACTIVE)
                .actualStartDate(actualStart)
                .targetEndDate(targetEnd)
                .build();
    }

    @Test
    void GivenAnUpgradeThatIsNotActive_WhenActivityOnADateIsChecked_ThenItIsNotActive() {
        HealthUpgrade upgrade = upgradeIn(UpgradeStatus.PAUSED);
        assertThat(upgrade.isActiveOn(REFERENCE_DAY)).isFalse();
    }
}
