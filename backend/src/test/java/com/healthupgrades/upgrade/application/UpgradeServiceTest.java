package com.healthupgrades.upgrade.application;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeActivated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCompleted;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCreated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePlanned;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.upgrade.application.port.in.UpgradeDetails;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.support.RecordingAuditTrail;
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
import java.util.List;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the upgrade use cases: FR-10 (create), FR-11 (list, narrowed by one filter), FR-12 (the
 * lifecycle transitions and the events they announce), FR-13 (edit at any point), FR-14 (delete) and
 * BR-15 (another user's upgrade is absent, not forbidden).
 *
 * <p>Also BR-5, the concurrent-HARD limit, on both routes into a running HARD upgrade — activating one,
 * and promoting an already-active one — because there are two and only one of them is obvious.
 *
 * <p>{@link UpgradeSchedulingService} is the real one rather than a mock: it is a pure domain service
 * with no collaborators, and stubbing its verdict would leave BR-5 asserted nowhere in this class.
 */
@ExtendWith(MockitoExtension.class)
class UpgradeServiceTest {

    @Mock UpgradeRepositoryPort repository;
    @Mock DomainEventPublisher eventPublisher;

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
        service = new UpgradeService(repository, schedulingService, eventPublisher, auditTrail, fixedClock);
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
        return new UpgradeDetails(null, "Cold showers", null, UpgradeType.HABIT,
                difficulty, null, null, null, null);
    }

    @Test
    void GivenTheHardLimitIsAlreadyReached_WhenAnActiveUpgradeIsPromotedToHard_ThenItIsRejected() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.MEDIUM)));
        when(repository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD)))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void GivenTheHardLimitIsNotReached_WhenAnActiveUpgradeIsPromotedToHard_ThenItSucceeds() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.ACTIVE, Difficulty.MEDIUM)));
        when(repository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD))
                .thenReturn(2L);
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthUpgrade saved = service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD));

        assertThat(saved.getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    void GivenAnUpgradeThatIsNotActive_WhenItIsPromotedToHard_ThenTheHardLimitIsNotConsulted() {
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.PLANNED, Difficulty.MEDIUM)));
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, upgradeId, detailsWithDifficulty(Difficulty.HARD));

        // Only ACTIVE upgrades occupy a HARD slot, so a planned promotion must not pay for the count query.
        verify(repository, never()).countByUserIdAndStatusAndDifficulty(any(), any(), any());
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

        verify(repository, never()).countByUserIdAndStatusAndDifficulty(any(), any(), any());
    }

    // ---- FR-10: creating ----

    @Test
    void GivenUpgradeDetails_WhenAnUpgradeIsCreated_ThenItIsStoredAsAnIdeaAndAnnounced() {
        when(repository.save(any(HealthUpgrade.class))).thenAnswer(call -> call.getArgument(0));

        HealthUpgrade created = service.create(userId, new UpgradeDetails(
                null, "Cold showers", "Every morning", UpgradeType.HABIT, Difficulty.MEDIUM,
                null, null, "Wake up properly", "30 days straight"));

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
        assertThatThrownBy(() -> service.create(userId, new UpgradeDetails(
                null, "  ", null, UpgradeType.HABIT, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
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

        service.update(userId, upgradeId, new UpgradeDetails(
                null, "Cold showers, shorter", "90 seconds", UpgradeType.HABIT, null,
                null, null, "Still worth it", "21 days"));

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

        assertThatThrownBy(() -> service.delete(userId, upgradeId))
                .isInstanceOf(ResourceNotFoundException.class);

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
        when(repository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.activate(userId, upgradeId, null))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(upgrade.getStatus()).isEqualTo(UpgradeStatus.PLANNED);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
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
    void GivenACompletedUpgrade_WhenItIsPaused_ThenItIsRejectedAndNothingIsSavedOrAnnounced() {
        // BR-3: COMPLETED is terminal. The guard lives on the aggregate; what this pins is that the
        // service does not save or announce anything once the aggregate has refused.
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.COMPLETED, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));

        assertThatThrownBy(() -> service.pause(userId, upgradeId))
                .isInstanceOf(BusinessRuleException.class);

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
    void GivenARunningUpgrade_WhenItIsCompleted_ThenTheTransitionIsAuditedAgainstItsOwner() {
        HealthUpgrade upgrade = upgradeWith(UpgradeStatus.ACTIVE, Difficulty.EASY);
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.of(upgrade));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(userId, upgradeId);

        assertThat(auditTrail.only(AuditAction.UPGRADE_COMPLETE)).hasValue(
                new AuditEvent(AuditAction.UPGRADE_COMPLETE, userId, upgradeId, AuditOutcome.ALLOWED));
    }

    @Test
    void GivenACompletedUpgrade_WhenItIsPaused_ThenTheRefusalIsAuditedRatherThanLost() {
        // The half of the trail that is easy to leave out: nothing changed, nothing was published, and
        // an attempt to move a terminal upgrade is exactly the entry somebody would come looking for.
        when(repository.findByIdAndUserId(upgradeId, userId))
                .thenReturn(Optional.of(upgradeWith(UpgradeStatus.COMPLETED, Difficulty.EASY)));

        assertThatThrownBy(() -> service.pause(userId, upgradeId))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(auditTrail.only(AuditAction.UPGRADE_PAUSE)).hasValue(
                new AuditEvent(AuditAction.UPGRADE_PAUSE, userId, upgradeId, AuditOutcome.REFUSED));
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItIsDeleted_ThenTheRefusedAttemptIsAudited() {
        // BR-15 reports a foreign record as absent, so the response says nothing happened. The trail is
        // the only place that records somebody having reached for a record that was not theirs.
        when(repository.findByIdAndUserId(upgradeId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(userId, upgradeId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(auditTrail.recorded(AuditAction.UPGRADE_DELETE, AuditOutcome.REFUSED)).isTrue();
    }
}
