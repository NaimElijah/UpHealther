package com.healthupgrades.upgrade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.event.DomainEvent;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.healtharea.application.port.in.HealthAreaQuery;
import com.healthupgrades.support.RecordingAuditTrail;
import com.healthupgrades.upgrade.application.port.in.UpgradeDetails;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeAbandoned;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeActivated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCompleted;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCreated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePaused;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePlanned;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.upgrade.domain.service.UpgradeSchedulingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the upgrade use cases: FR-10 (create), FR-11 (list, narrowed by one filter), FR-12 (the
 * lifecycle transitions and the events they announce), FR-13 (edit at any point), FR-14 (delete),
 * BR-15 (another user's upgrade is absent, not forbidden), BR-18 (an upgrade is filed only under the
 * caller's own health area) and NFR-15 (every announcement is timed by the injected clock).
 *
 * <p>Also BR-5, the concurrent-HARD limit, on both routes into a running HARD upgrade — activating one,
 * and promoting an already-active one — because there are two and only one of them is obvious.
 *
 * <p>{@link UpgradeSchedulingService} is the real one rather than a mock: it is a pure domain service
 * with no collaborators, and stubbing its verdict would leave BR-5 asserted nowhere in this class.
 */
@ExtendWith(MockitoExtension.class)
class UpgradeServiceTest {

    @Mock
    UpgradeRepositoryPort repository;

    @Mock
    DomainEventPublisher eventPublisher;

    @Mock
    HealthAreaQuery healthAreaQuery;

    /** The HARD-limit rule is a pure domain service, not a port — exercise the real one. */
    private final UpgradeSchedulingService schedulingService = new UpgradeSchedulingService();

    private UpgradeService service;
    /** A real implementation, not a mock: AuditTrail.recording is a default method. */
    private final RecordingAuditTrail auditTrail = new RecordingAuditTrail();

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UpgradeService(
                repository, schedulingService, eventPublisher, auditTrail, healthAreaQuery, fixedClock);
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

    private UpgradeDetails detailsWithDifficulty(Difficulty difficulty) {
        return new UpgradeDetails(null, "Cold showers", null, UpgradeType.HABIT, difficulty, null, null, null, null);
    }

    @Test
    void GivenTheHardLimitIsAlreadyReached_WhenAnActiveUpgradeIsPromotedToHard_ThenItIsRejected() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.MEDIUM)));
        when(repository.countActiveHardUpgradesForLimitCheck(userId)).thenReturn(3L);

        assertThatThrownBy(() -> service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD)))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void GivenTheHardLimitIsNotReached_WhenAnActiveUpgradeIsPromotedToHard_ThenItSucceeds() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.MEDIUM)));
        when(repository.countActiveHardUpgradesForLimitCheck(userId)).thenReturn(2L);
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthUpgrade saved = service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD));

        assertThat(saved.getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    void
            GivenAnotherHardLimitCheckInProgress_WhenAnActiveUpgradeIsPromotedToHard_ThenItIsRefusedAsRetryableAndNothingChanges() {
        // BR-5 under concurrency (ADR-074). The claim is the count's, so a refused claim is a refused
        // promotion: nothing is saved, and the aggregate keeps its difficulty for the resend.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.ACTIVE, Difficulty.MEDIUM);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.countActiveHardUpgradesForLimitCheck(userId))
                .thenThrow(new RetryableConflictException("check in progress"));

        assertThatThrownBy(() -> service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD)))
                .isInstanceOf(RetryableConflictException.class);

        assertThat(upgrade.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnUpgradeThatIsNotActive_WhenItIsPromotedToHard_ThenTheHardLimitIsNotConsulted() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD));

        // Only ACTIVE upgrades occupy a HARD slot, so a planned promotion must not pay for the count query,
        // nor be refused because another request is checking.
        verify(repository, never()).countActiveHardUpgradesForLimitCheck(any());
    }

    @Test
    void GivenAnAbandonedUpgrade_WhenItIsRescheduled_ThenItIsAnnouncedAsPlannedAgain() {
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
    void GivenAnUpgradeThatKeepsItsStatus_WhenOnlyItsDateIsMoved_ThenNothingIsAnnounced() {
        // No status transition happened, so there is nothing for a listener to react to.
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reschedule(userId, upgradeId, LocalDate.of(2026, 4, 1));

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenAnUnchangedDifficulty_WhenAnUpgradeIsUpdated_ThenTheHardLimitIsNotConsulted() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.HARD)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD));

        verify(repository, never()).countActiveHardUpgradesForLimitCheck(any());
    }

    // ---- FR-10: creating ----

    @Test
    void GivenUpgradeDetails_WhenAnUpgradeIsCreated_ThenItIsStoredAsAnIdeaAndAnnounced() {
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(call -> call.getArgument(0));

        HealthUpgrade created = service.create(
                userId,
                new UpgradeDetails(
                        null,
                        "Cold showers",
                        "Every morning",
                        UpgradeType.HABIT,
                        Difficulty.MEDIUM,
                        null,
                        null,
                        "Wake up properly",
                        "30 days straight"));

        assertThat(created.getStatus()).isEqualTo(UpgradeStatus.IDEA);
        assertThat(created.getUserId()).isEqualTo(userId);
        assertThat(created.getTitle()).isEqualTo("Cold showers");

        ArgumentCaptor<HealthUpgradeCreated> event = ArgumentCaptor.forClass(HealthUpgradeCreated.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(userId);
        assertThat(event.getValue().title()).isEqualTo("Cold showers");
    }

    @Test
    void GivenDetailsMissingATitle_WhenAnUpgradeIsCreated_ThenNothingIsStoredOrAnnounced() {
        // The aggregate refuses it (BR-4); what this pins is that the refusal happens before the save,
        // so a rejected create leaves no row and no event behind.
        assertThatThrownBy(() -> service.create(
                        userId, new UpgradeDetails(null, "  ", null, UpgradeType.HABIT, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    // ---- BR-18: an upgrade is filed only under the caller's own area ----

    private UpgradeDetails detailsInArea(UUID areaId) {
        return new UpgradeDetails(
                areaId, "Cold showers", null, UpgradeType.HABIT, Difficulty.MEDIUM, null, null, null, null);
    }

    @Test
    void GivenAnAreaOwnedBySomebodyElse_WhenAnUpgradeIsCreated_ThenItIsRefusedAndNothingIsSaved() {
        UUID foreignArea = UUID.randomUUID();
        when(healthAreaQuery.ownsArea(userId, foreignArea)).thenReturn(false);

        assertThatThrownBy(() -> service.create(userId, detailsInArea(foreignArea)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Unknown health area");

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenARefusedArea_WhenTheCreateIsAudited_ThenTheAttemptIsRecordedAsRefused() {
        UUID foreignArea = UUID.randomUUID();
        when(healthAreaQuery.ownsArea(userId, foreignArea)).thenReturn(false);

        assertThatThrownBy(() -> service.create(userId, detailsInArea(foreignArea)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(auditTrail.recorded(AuditAction.UPGRADE_CREATE, AuditOutcome.REFUSED))
                .isTrue();
    }

    @Test
    void GivenAnAreaTheCallerOwns_WhenAnUpgradeIsCreated_ThenItIsFiledUnderIt() {
        UUID ownArea = UUID.randomUUID();
        when(healthAreaQuery.ownsArea(userId, ownArea)).thenReturn(true);
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(call -> call.getArgument(0));

        HealthUpgrade created = service.create(userId, detailsInArea(ownArea));

        assertThat(created.getAreaId()).isEqualTo(ownArea);
    }

    @Test
    void GivenNoArea_WhenAnUpgradeIsCreated_ThenNoOwnershipLookupIsMade() {
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, detailsInArea(null));

        verify(healthAreaQuery, never()).ownsArea(any(), any());
    }

    @Test
    void GivenAnUpgradeMovedToAForeignArea_WhenItIsUpdated_ThenItIsRefusedAndLeftUntouched() {
        UUID foreignArea = UUID.randomUUID();
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(healthAreaQuery.ownsArea(userId, foreignArea)).thenReturn(false);

        assertThatThrownBy(() -> service.update(userId, upgradeId, detailsInArea(foreignArea)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Unknown health area");

        assertThat(upgrade.getAreaId()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnUnchangedArea_WhenTheUpgradeIsUpdated_ThenNoOwnershipLookupBlocksIt() {
        // An upgrade filed before this rule existed may point at an area the check would now refuse;
        // editing its title must not become impossible because of a field the user did not touch.
        UUID existingArea = UUID.randomUUID();
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM);
        upgrade.updateDetails(existingArea, "Cold showers", null, UpgradeType.HABIT, null, null, null);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, upgradeId, detailsInArea(existingArea));

        verify(healthAreaQuery, never()).ownsArea(any(), any());
    }

    // ---- FR-11: listing ----

    @Test
    void GivenNoFilter_WhenUpgradesAreListed_ThenAllOfTheUsersAreReturned() {
        when(repository.findByUserId(userId)).thenReturn(List.of());

        service.findAll(userId, null, null, null, null);

        verify(repository).findByUserId(userId);
    }

    @Test
    void GivenAStatusFilter_WhenUpgradesAreListed_ThenOnlyThatStatusIsQueried() {
        when(repository.findByUserIdAndStatus(userId, UpgradeStatus.ACTIVE)).thenReturn(List.of());

        service.findAll(userId, UpgradeStatus.ACTIVE, null, null, null);

        verify(repository).findByUserIdAndStatus(userId, UpgradeStatus.ACTIVE);
    }

    @Test
    void GivenATypeFilter_WhenUpgradesAreListed_ThenOnlyThatTypeIsQueried() {
        when(repository.findByUserIdAndType(userId, UpgradeType.HABIT)).thenReturn(List.of());

        service.findAll(userId, null, UpgradeType.HABIT, null, null);

        verify(repository).findByUserIdAndType(userId, UpgradeType.HABIT);
    }

    @Test
    void GivenAnAreaFilter_WhenUpgradesAreListed_ThenOnlyThatAreaIsQueried() {
        UUID areaId = UUID.randomUUID();
        when(repository.findByUserIdAndAreaId(userId, areaId)).thenReturn(List.of());

        service.findAll(userId, null, null, areaId, null);

        verify(repository).findByUserIdAndAreaId(userId, areaId);
    }

    @Test
    void GivenADifficultyFilter_WhenUpgradesAreListed_ThenOnlyThatDifficultyIsQueried() {
        when(repository.findByUserIdAndDifficulty(userId, Difficulty.HARD)).thenReturn(List.of());

        service.findAll(userId, null, null, null, Difficulty.HARD);

        verify(repository).findByUserIdAndDifficulty(userId, Difficulty.HARD);
    }

    @Test
    void GivenSeveralFiltersAtOnce_WhenUpgradesAreListed_ThenTheFirstOneWinsRatherThanBeingCombined() {
        // The filters are alternatives, not a conjunction — status, then type, then area, then
        // difficulty. A caller expecting an AND gets the status query, and this is where that is said.
        when(repository.findByUserIdAndStatus(userId, UpgradeStatus.ACTIVE)).thenReturn(List.of());

        service.findAll(userId, UpgradeStatus.ACTIVE, UpgradeType.HABIT, UUID.randomUUID(), Difficulty.HARD);

        verify(repository).findByUserIdAndStatus(userId, UpgradeStatus.ACTIVE);
        verify(repository, never()).findByUserIdAndType(any(), any());
        verify(repository, never()).findByUserIdAndAreaId(any(), any());
        verify(repository, never()).findByUserIdAndDifficulty(any(), any());
    }

    // ---- FR-13: editing ----

    @Test
    void GivenAnUpgradeInAnyState_WhenItsDetailsAreEdited_ThenTheEditableFieldsChangeAndTheStatusDoesNot() {
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.ACTIVE, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(upgrade)).thenReturn(upgrade);

        service.update(
                userId,
                upgradeId,
                new UpgradeDetails(
                        null,
                        "Cold showers, shorter",
                        "90 seconds",
                        UpgradeType.HABIT,
                        null,
                        null,
                        null,
                        "Still worth it",
                        "21 days"));

        assertThat(upgrade.getTitle()).isEqualTo("Cold showers, shorter");
        assertThat(upgrade.getDescription()).isEqualTo("90 seconds");
        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItIsEdited_ThenItIsReportedAsAbsentAndNothingIsSaved() {
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(userId, upgradeId, detailsWithDifficulty(null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    // ---- FR-14: deleting ----

    @Test
    void GivenAnOwnedUpgrade_WhenItIsDeleted_ThenItIsRemovedAndNothingIsAnnounced() {
        // Nothing is published on purpose: the aggregate is gone, so there is nothing left for a
        // listener to act on — a notification pointing at a deleted upgrade is a dead link in the inbox.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.IDEA, null);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));

        service.delete(userId, upgradeId);

        verify(repository).delete(upgrade);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItIsDeleted_ThenNothingIsRemoved() {
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(userId, upgradeId)).isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(any());
    }

    // ---- FR-12: the transitions announce themselves ----

    @Test
    void GivenAPlannedUpgrade_WhenItIsActivated_ThenTheActivationIsAnnounced() {
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.PLANNED, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(upgrade)).thenReturn(upgrade);

        service.activate(userId, upgradeId, null);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.ACTIVE);
        verify(eventPublisher).publish(any(HealthUpgradeActivated.class));
    }

    @Test
    void GivenNoStartDateOnActivation_WhenAnUpgradeIsActivated_ThenItStartsOnTheInjectedClocksToday() {
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.PLANNED, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(upgrade)).thenReturn(upgrade);

        service.activate(userId, upgradeId, null);

        assertThat(upgrade.getActualStartDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void GivenTheHardLimitIsAlreadyReached_WhenAnotherHardUpgradeIsActivated_ThenItIsRejectedAndNothingIsSaved() {
        // BR-5 on the activation route. The limit counts ACTIVE HARD upgrades, so this is the check
        // that stops a fourth one starting.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.PLANNED, Difficulty.HARD);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.countActiveHardUpgradesForLimitCheck(userId)).thenReturn(3L);

        assertThatThrownBy(() -> service.activate(userId, upgradeId, null)).isInstanceOf(BusinessRuleException.class);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void
            GivenAnotherHardLimitCheckInProgress_WhenAHardUpgradeIsActivated_ThenItIsRefusedAsRetryableAndNothingChanges() {
        // BR-5 under concurrency (ADR-074): two tabs activating two HARD upgrades must not both count two.
        // The second is refused before the transition, so the resend finds the upgrade as it was.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.PLANNED, Difficulty.HARD);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.countActiveHardUpgradesForLimitCheck(userId))
                .thenThrow(new RetryableConflictException("check in progress"));

        assertThatThrownBy(() -> service.activate(userId, upgradeId, null))
                .isInstanceOf(RetryableConflictException.class);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = UpgradeStatus.class,
            names = {"ACTIVE", "COMPLETED"})
    void GivenAHardUpgradeThatCannotBeActivated_WhenItIsActivatedWithTheLimitReached_ThenTheTransitionIsWhatRefusesIt(
            UpgradeStatus status) {
        // D3.2. An upgrade the transition refuses claims no slot, so its refusal must be the transition's,
        // not the HARD limit's, and it must not take the user's limit claim either.
        HealthUpgrade upgrade = upgradeWith(status, Difficulty.HARD);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        lenient().when(repository.countActiveHardUpgradesForLimitCheck(userId)).thenReturn(3L);

        assertThatThrownBy(() -> service.activate(userId, upgradeId, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Only PLANNED or PAUSED upgrades can be activated");

        verify(repository, never()).countActiveHardUpgradesForLimitCheck(any());
    }

    @Test
    void GivenAnUpgradeThatIsNotHard_WhenItIsActivated_ThenTheHardLimitIsNotChecked() {
        // An EASY or MEDIUM upgrade claims no HARD slot, so it neither counts nor can be refused because
        // another request is checking.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(upgrade)).thenReturn(upgrade);

        service.activate(userId, upgradeId, null);

        verify(repository, never()).countActiveHardUpgradesForLimitCheck(any());
    }

    @Test
    void GivenAnActiveUpgrade_WhenItIsCompleted_ThenTheCompletionIsAnnounced() {
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.ACTIVE, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(upgrade)).thenReturn(upgrade);

        service.complete(userId, upgradeId);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.COMPLETED);
        verify(eventPublisher).publish(any(HealthUpgradeCompleted.class));
    }

    @Test
    void GivenAnActiveUpgrade_WhenItIsCompleted_ThenItRecordsTheInjectedClocksNow() {
        // ADR-080. The same instant the completion event carries, so the two never disagree.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.ACTIVE, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(upgrade)).thenReturn(upgrade);

        service.complete(userId, upgradeId);

        assertThat(upgrade.getCompletedAt()).isEqualTo(LocalDateTime.now(fixedClock));
    }

    @Test
    void GivenACompletedUpgrade_WhenItIsPaused_ThenItIsRejectedAndNothingIsSavedOrAnnounced() {
        // BR-3: COMPLETED is terminal. The guard lives on the aggregate; what this pins is that the
        // service does not save or announce anything once the aggregate has refused.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.COMPLETED, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));

        assertThatThrownBy(() -> service.pause(userId, upgradeId)).isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    // ---- The inbound port other contexts read through ----

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenAnotherContextResolvesIt_ThenItIsReportedAsAbsent() {
        // BR-15's single choke point: tracking, reflections and reminders all establish ownership
        // through this one method.
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnedUpgrade(userId, upgradeId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItIsLookedUpWithoutThrowing_ThenNothingComesBack() {
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.empty());

        assertThat(service.findOwned(userId, upgradeId)).isEmpty();
    }

    // ---- The audit trail ----

    @Test
    void GivenACompletedUpgrade_WhenItIsPaused_ThenTheRefusalIsAuditedRatherThanLost() {
        // The half of the trail that is easy to leave out: nothing changed, nothing was published, and
        // an attempt to move a terminal upgrade is exactly the entry somebody would come looking for.
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.COMPLETED, Difficulty.EASY)));

        assertThatThrownBy(() -> service.pause(userId, upgradeId)).isInstanceOf(BusinessRuleException.class);

        assertThat(auditTrail.only(AuditAction.UPGRADE_PAUSE))
                .hasValue(new AuditEvent(AuditAction.UPGRADE_PAUSE, userId, upgradeId, AuditOutcome.REFUSED));
    }

    // NFR-23 for every use case that changes an upgrade: one entry, under its own action, allowed when
    // the move went through and refused when the upgrade was somebody else's.

    /** A state-changing use case, run against this class's upgrade and owner. */
    @FunctionalInterface
    private interface AuditedUseCase {
        void run(UpgradeService service, UUID userId, UUID upgradeId);
    }

    /** Every use case on an existing upgrade, its audit action, and a status it is allowed from. */
    static Stream<Arguments> everyAuditedUseCaseOnAnUpgrade() {
        LocalDate date = LocalDate.of(2026, 4, 1);
        UpgradeDetails edit =
                new UpgradeDetails(null, "Cold showers", null, UpgradeType.HABIT, null, null, null, null, null);
        return Stream.of(
                Arguments.of(AuditAction.UPGRADE_UPDATE, UpgradeStatus.ACTIVE, (AuditedUseCase)
                        (s, user, id) -> s.update(user, id, edit)),
                Arguments.of(AuditAction.UPGRADE_DELETE, UpgradeStatus.ACTIVE, (AuditedUseCase) UpgradeService::delete),
                Arguments.of(AuditAction.UPGRADE_PLAN, UpgradeStatus.IDEA, (AuditedUseCase)
                        (s, user, id) -> s.plan(user, id, date)),
                Arguments.of(AuditAction.UPGRADE_ACTIVATE, UpgradeStatus.PLANNED, (AuditedUseCase)
                        (s, user, id) -> s.activate(user, id, date)),
                Arguments.of(AuditAction.UPGRADE_PAUSE, UpgradeStatus.ACTIVE, (AuditedUseCase) UpgradeService::pause),
                Arguments.of(
                        AuditAction.UPGRADE_COMPLETE, UpgradeStatus.ACTIVE, (AuditedUseCase) UpgradeService::complete),
                Arguments.of(
                        AuditAction.UPGRADE_ABANDON, UpgradeStatus.ACTIVE, (AuditedUseCase) UpgradeService::abandon),
                Arguments.of(AuditAction.UPGRADE_RESCHEDULE, UpgradeStatus.ABANDONED, (AuditedUseCase)
                        (s, user, id) -> s.reschedule(user, id, date)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAuditedUseCaseOnAnUpgrade")
    void GivenAnOwnedUpgradeTheMoveIsAllowedFrom_WhenTheUseCaseRuns_ThenItAloneIsAuditedAsAllowed(
            AuditAction action, UpgradeStatus allowedFrom, AuditedUseCase useCase) {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(allowedFrom, Difficulty.MEDIUM)));
        // Delete never saves, so this stub is not used on every row.
        lenient().when(repository.save(any(HealthUpgrade.class))).thenAnswer(call -> call.getArgument(0));

        useCase.run(service, userId, upgradeId);

        assertThat(auditTrail.recorded()).containsExactly(AuditEvent.allowed(action, userId, upgradeId));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAuditedUseCaseOnAnUpgrade")
    void GivenAnUpgradeOwnedBySomebodyElse_WhenTheUseCaseRuns_ThenItAloneIsAuditedAsRefused(
            AuditAction action, UpgradeStatus allowedFrom, AuditedUseCase useCase) {
        // BR-15 reports a foreign record as absent, so the response says nothing happened. The trail is
        // the only place that records somebody having reached for a record that was not theirs.
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.run(service, userId, upgradeId)).isInstanceOf(ResourceNotFoundException.class);

        assertThat(auditTrail.recorded())
                .containsExactly(new AuditEvent(action, userId, upgradeId, AuditOutcome.REFUSED));
    }

    @Test
    void GivenValidDetails_WhenAnUpgradeIsCreated_ThenTheCreationIsAuditedAgainstTheNewId() {
        UUID createdId = UUID.randomUUID();
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(call -> {
            HealthUpgrade toSave = call.getArgument(0);
            return HealthUpgrade.builder()
                    .id(createdId)
                    .userId(toSave.getUserId())
                    .title(toSave.getTitle())
                    .type(toSave.getType())
                    .build();
        });

        service.create(userId, detailsWithDifficulty(null));

        assertThat(auditTrail.recorded())
                .containsExactly(AuditEvent.allowed(AuditAction.UPGRADE_CREATE, userId, createdId));
    }

    // ---- NFR-15: an announcement is timed by the injected clock ----

    /** A use case that announces what it did, run against this class's upgrade and owner. */
    @FunctionalInterface
    private interface AnnouncingUseCase {
        void run(UpgradeService service, UUID userId, UUID upgradeId);
    }

    /**
     * Every use case that publishes, with the status its upgrade has to start in — none for create — and
     * the event it announces, which FR-28's notifications are chosen by.
     */
    static Stream<Arguments> everyUseCaseThatAnnounces() {
        LocalDate date = LocalDate.of(2026, 4, 1);
        return Stream.of(
                Arguments.of("create", null, HealthUpgradeCreated.class, (AnnouncingUseCase) (s, user, id) -> s.create(
                        user,
                        new UpgradeDetails(
                                null, "Cold showers", null, UpgradeType.HABIT, null, null, null, null, null))),
                Arguments.of("plan", UpgradeStatus.IDEA, HealthUpgradePlanned.class, (AnnouncingUseCase)
                        (s, user, id) -> s.plan(user, id, date)),
                Arguments.of("activate", UpgradeStatus.PLANNED, HealthUpgradeActivated.class, (AnnouncingUseCase)
                        (s, user, id) -> s.activate(user, id, null)),
                Arguments.of("pause", UpgradeStatus.ACTIVE, HealthUpgradePaused.class, (AnnouncingUseCase)
                        (s, user, id) -> s.pause(user, id)),
                Arguments.of("complete", UpgradeStatus.ACTIVE, HealthUpgradeCompleted.class, (AnnouncingUseCase)
                        (s, user, id) -> s.complete(user, id)),
                Arguments.of("abandon", UpgradeStatus.ACTIVE, HealthUpgradeAbandoned.class, (AnnouncingUseCase)
                        (s, user, id) -> s.abandon(user, id)),
                Arguments.of("reschedule", UpgradeStatus.ABANDONED, HealthUpgradePlanned.class, (AnnouncingUseCase)
                        (s, user, id) -> s.reschedule(user, id, date)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyUseCaseThatAnnounces")
    void GivenAUseCaseThatAnnounces_WhenItRuns_ThenItsOwnEventIsAnnouncedTimedByTheInjectedClock(
            String useCase,
            UpgradeStatus startingStatus,
            Class<? extends DomainEvent> announces,
            AnnouncingUseCase announcing) {
        // #100: the wall clock stamps whatever time and zone the host happens to have.
        if (startingStatus != null) {
            when(repository.findByIdAndUserId(upgradeId, userId))
                    .thenReturn(Optional.of(upgradeWith(startingStatus, Difficulty.MEDIUM)));
        }
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(call -> call.getArgument(0));

        announcing.run(service, userId, upgradeId);

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue()).isInstanceOf(announces);
        assertThat(event.getValue().occurredAt()).isEqualTo(LocalDateTime.now(fixedClock));
    }
}
