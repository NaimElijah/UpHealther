package com.healthupgrades.upgrade.application;
import com.healthupgrades.upgrade.domain.service.UpgradeSchedulingService;

import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.upgrade.domain.event.*;
import com.healthupgrades.upgrade.application.port.in.UpgradeDetails;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.*;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service orchestrating the health-upgrade lifecycle.
 *
 * <p>Each state change follows the pattern: load the aggregate, invoke the domain method (which guards the
 * transition), save via the repository port, publish a domain event, and return the domain aggregate.
 * Command methods return {@link HealthUpgrade}; the web adapter maps it to a DTO. Implements the
 * {@link UpgradeQuery} inbound port so other contexts read upgrades as domain objects.
 */
@Service
@RequiredArgsConstructor
public class UpgradeService implements UpgradeQuery {

    private final UpgradeRepositoryPort repository; // outbound persistence port
    private final UpgradeSchedulingService schedulingService; // pure domain invariant
    private final DomainEventPublisher eventPublisher; // in-process domain events
    private final AuditTrail auditTrail; // records the attempt, allowed or refused
    private final Clock clock; // decides the start date an activation defaults to

    /**
     * Creates a new upgrade in the IDEA state and announces it.
     *
     * @param userId  the owner
     * @param details the attributes to store
     * @return the persisted aggregate
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the title or type is missing
     */
    @Transactional
    public HealthUpgrade create(UUID userId, UpgradeDetails details) {
        return auditTrail.recordingCreation(AuditAction.UPGRADE_CREATE, userId, () -> {
            HealthUpgrade created = HealthUpgrade.create(userId, details.areaId(), details.title(), details.description(),
                    details.type(), details.difficulty(), details.plannedStartDate(), details.targetEndDate(),
                    details.motivation(), details.successCriteria());
            HealthUpgrade saved = repository.save(created);
            eventPublisher.publish(new HealthUpgradeCreated(saved.getId(), userId, saved.getTitle(), LocalDateTime.now()));
            return saved;
        }, HealthUpgrade::getId);
    }

    /**
     * Lists a user's upgrades, narrowed by at most one filter.
     *
     * <p>The filters are not combined: the first non-null one wins, in the order status, type, area,
     * difficulty. A caller that needs a conjunction has to narrow the result itself.
     *
     * @param userId     the owner
     * @param status     lifecycle filter, or null
     * @param type       type filter, or null
     * @param areaId     health-area filter, or null
     * @param difficulty difficulty filter, or null
     * @return the matching upgrades, or all of the user's when every filter is null
     */
    public List<HealthUpgrade> findAll(UUID userId, UpgradeStatus status, UpgradeType type, UUID areaId, Difficulty difficulty) {
        if (status != null) return repository.findByUserIdAndStatus(userId, status);
        if (type != null) return repository.findByUserIdAndType(userId, type);
        if (areaId != null) return repository.findByUserIdAndAreaId(userId, areaId);
        if (difficulty != null) return repository.findByUserIdAndDifficulty(userId, difficulty);
        return repository.findByUserId(userId);
    }

    /**
     * Updates the editable fields of an owned upgrade, and its difficulty when that changed.
     *
     * <p>Promoting an already-ACTIVE upgrade to HARD is one of the two ways to occupy a HARD slot, so
     * the limit is checked here as well as on activation — and checked before anything is mutated, so a
     * rejected update leaves the aggregate untouched.
     *
     * @param userId  the owner
     * @param id      the upgrade's identifier
     * @param details the replacement attributes
     * @return the saved aggregate
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the title or type is
     *         missing, or the change would exceed the concurrent-HARD limit
     */
    @Transactional
    public HealthUpgrade update(UUID userId, UUID id, UpgradeDetails details) {
        return auditTrail.recording(AuditAction.UPGRADE_UPDATE, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            boolean difficultyChanges = details.difficulty() != null && details.difficulty() != upgrade.getDifficulty();
            if (difficultyChanges) {
                // Fail before mutating anything.
                validateHardLimit(userId, details.difficulty(), upgrade.getStatus() == UpgradeStatus.ACTIVE);
            }

            upgrade.updateDetails(details.areaId(), details.title(), details.description(), details.type(),
                    details.targetEndDate(), details.motivation(), details.successCriteria());
            if (difficultyChanges) upgrade.changeDifficulty(details.difficulty());
            return repository.save(upgrade);
        });
    }

    /**
     * Applies the max-concurrent-HARD invariant to an upgrade that would run at {@code difficulty}.
     *
     * <p>An upgrade occupies a HARD slot only while ACTIVE, so {@code willRunActive} says whether this
     * upgrade would hold one once the operation completes — true when activating, and true when
     * promoting an already-ACTIVE upgrade. Otherwise, and for uncapped difficulties, the count query is
     * skipped entirely.
     */
    private void validateHardLimit(UUID userId, Difficulty difficulty, boolean willRunActive) {
        long activeHardCount = difficulty == Difficulty.HARD && willRunActive
                ? repository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD)
                : 0L;
        schedulingService.validateWithinHardLimit(difficulty, activeHardCount);
    }

    /**
     * Deletes an owned upgrade.
     *
     * <p>Publishes nothing: the upgrade is gone, so there is no aggregate left for a listener to act on.
     *
     * @param userId the owner
     * @param id     the upgrade's identifier
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     */
    @Transactional
    public void delete(UUID userId, UUID id) {
        auditTrail.recording(AuditAction.UPGRADE_DELETE, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            repository.delete(upgrade);
        });
    }

    /**
     * Commits an owned idea to a start date: IDEA to PLANNED.
     *
     * @param userId           the owner
     * @param id               the upgrade's identifier
     * @param plannedStartDate the intended start date
     * @return the saved aggregate
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if it is not an IDEA
     */
    @Transactional
    public HealthUpgrade plan(UUID userId, UUID id, LocalDate plannedStartDate) {
        return auditTrail.recording(AuditAction.UPGRADE_PLAN, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            upgrade.plan(plannedStartDate);
            HealthUpgrade saved = repository.save(upgrade);
            eventPublisher.publish(new HealthUpgradePlanned(saved.getId(), userId, plannedStartDate, LocalDateTime.now()));
            return saved;
        });
    }

    /**
     * Starts or resumes an owned upgrade: PLANNED or PAUSED to ACTIVE.
     *
     * <p>The HARD limit is checked before the transition, so a rejected activation leaves the aggregate
     * as it was.
     *
     * @param userId    the owner
     * @param id        the upgrade's identifier
     * @param startDate the date it starts running; today by the injected clock when null
     * @return the saved aggregate
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if it is not PLANNED or
     *         PAUSED, or activating it would exceed the concurrent-HARD limit
     */
    @Transactional
    public HealthUpgrade activate(UUID userId, UUID id, LocalDate startDate) {
        return auditTrail.recording(AuditAction.UPGRADE_ACTIVATE, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            validateHardLimit(userId, upgrade.getDifficulty(), true); // activation always claims a slot
            upgrade.activate(startDate != null ? startDate : LocalDate.now(clock));
            HealthUpgrade saved = repository.save(upgrade);
            eventPublisher.publish(new HealthUpgradeActivated(saved.getId(), userId, saved.getActualStartDate(), LocalDateTime.now()));
            return saved;
        });
    }

    /**
     * Suspends an owned running upgrade: ACTIVE to PAUSED, releasing any HARD slot it held.
     *
     * @param userId the owner
     * @param id     the upgrade's identifier
     * @return the saved aggregate
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if it is not ACTIVE
     */
    @Transactional
    public HealthUpgrade pause(UUID userId, UUID id) {
        return auditTrail.recording(AuditAction.UPGRADE_PAUSE, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            upgrade.pause();
            HealthUpgrade saved = repository.save(upgrade);
            eventPublisher.publish(new HealthUpgradePaused(saved.getId(), userId, LocalDateTime.now()));
            return saved;
        });
    }

    /**
     * Finishes an owned running upgrade: ACTIVE to COMPLETED, which is terminal.
     *
     * @param userId the owner
     * @param id     the upgrade's identifier
     * @return the saved aggregate
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if it is not ACTIVE
     */
    @Transactional
    public HealthUpgrade complete(UUID userId, UUID id) {
        return auditTrail.recording(AuditAction.UPGRADE_COMPLETE, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            upgrade.complete();
            HealthUpgrade saved = repository.save(upgrade);
            eventPublisher.publish(new HealthUpgradeCompleted(saved.getId(), userId, LocalDateTime.now()));
            return saved;
        });
    }

    /**
     * Gives an owned upgrade up: to ABANDONED.
     *
     * @param userId the owner
     * @param id     the upgrade's identifier
     * @return the saved aggregate
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if it is already
     *         COMPLETED or ABANDONED
     */
    @Transactional
    public HealthUpgrade abandon(UUID userId, UUID id) {
        return auditTrail.recording(AuditAction.UPGRADE_ABANDON, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            upgrade.abandon();
            HealthUpgrade saved = repository.save(upgrade);
            eventPublisher.publish(new HealthUpgradeAbandoned(saved.getId(), userId, LocalDateTime.now()));
            return saved;
        });
    }

    /**
     * Moves an owned upgrade's planned start date, reviving an abandoned one into PLANNED.
     *
     * @param userId  the owner
     * @param id      the upgrade's identifier
     * @param newDate the new planned start date
     * @return the saved aggregate
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if it is COMPLETED
     */
    @Transactional
    public HealthUpgrade reschedule(UUID userId, UUID id, LocalDate newDate) {
        return auditTrail.recording(AuditAction.UPGRADE_RESCHEDULE, userId, id, () -> {
            HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
            UpgradeStatus statusBefore = upgrade.getStatus();
            upgrade.reschedule(newDate);
            HealthUpgrade saved = repository.save(upgrade);

            // Rescheduling an abandoned upgrade revives it into PLANNED. That is a real lifecycle
            // transition and has to be announced, or listeners see it silently reappear as planned.
            if (saved.getStatus() == UpgradeStatus.PLANNED && statusBefore != UpgradeStatus.PLANNED) {
                eventPublisher.publish(new HealthUpgradePlanned(saved.getId(), userId, newDate, LocalDateTime.now()));
            }
            return saved;
        });
    }

    // ---- UpgradeQuery (inbound port) ----

    /** {@inheritDoc} */
    @Override
    public HealthUpgrade getOwnedUpgrade(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Upgrade not found: " + id));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<HealthUpgrade> findOwned(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByUser(UUID userId) {
        return repository.findByUserId(userId);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findByStatus(UpgradeStatus status) {
        return repository.findByStatus(status);
    }

    /** {@inheritDoc} */
    @Override
    public List<HealthUpgrade> findAllById(Iterable<UUID> ids) {
        return repository.findAllById(ids);
    }
}
