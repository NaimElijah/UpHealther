package com.healthupgrades.upgrade.application;
import com.healthupgrades.upgrade.domain.service.UpgradeSchedulingService;

import com.healthupgrades.common.domain.event.*;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeRequest;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.model.*;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Creates a new upgrade in the IDEA state and publishes a creation event. */
    @Transactional
    public HealthUpgrade create(UUID userId, UpgradeRequest req) {
        HealthUpgrade upgrade = HealthUpgrade.builder()
                .userId(userId)
                .areaId(req.areaId())
                .title(req.title())
                .description(req.description())
                .type(req.type())
                .status(UpgradeStatus.IDEA)
                .difficulty(req.difficulty())
                .plannedStartDate(req.plannedStartDate())
                .targetEndDate(req.targetEndDate())
                .motivation(req.motivation())
                .successCriteria(req.successCriteria())
                .build();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeCreated(upgrade.getId(), userId, upgrade.getTitle(), LocalDateTime.now()));
        return upgrade;
    }

    /** Lists a user's upgrades, optionally narrowed by the first non-null filter. */
    public List<HealthUpgrade> findAll(UUID userId, UpgradeStatus status, UpgradeType type, UUID areaId, Difficulty difficulty) {
        if (status != null) return repository.findByUserIdAndStatus(userId, status); // filter by status
        if (type != null) return repository.findByUserIdAndType(userId, type); // filter by type
        if (areaId != null) return repository.findByUserIdAndAreaId(userId, areaId); // filter by area
        if (difficulty != null) return repository.findByUserIdAndDifficulty(userId, difficulty); // filter by difficulty
        return repository.findByUserId(userId); // no filter -> all
    }

    /** Updates the editable fields of an owned upgrade. */
    @Transactional
    public HealthUpgrade update(UUID userId, UUID id, UpgradeRequest req) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        upgrade.setAreaId(req.areaId());
        upgrade.setTitle(req.title());
        upgrade.setDescription(req.description());
        upgrade.setType(req.type());
        upgrade.setTargetEndDate(req.targetEndDate());
        upgrade.setMotivation(req.motivation());
        upgrade.setSuccessCriteria(req.successCriteria());
        if (req.difficulty() != null) upgrade.changeDifficulty(req.difficulty()); // guarded by the entity
        return repository.save(upgrade);
    }

    /** Deletes an owned upgrade. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        repository.delete(upgrade);
    }

    /** Moves an owned upgrade to PLANNED with a planned start date. */
    @Transactional
    public HealthUpgrade plan(UUID userId, UUID id, LocalDate plannedStartDate) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        upgrade.plan(plannedStartDate);
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradePlanned(upgrade.getId(), userId, plannedStartDate, LocalDateTime.now()));
        return upgrade;
    }

    /** Activates an owned upgrade, enforcing the max-concurrent-HARD invariant first. */
    @Transactional
    public HealthUpgrade activate(UUID userId, UUID id, LocalDate startDate) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        // Only HARD activations are capped, so avoid the count query entirely for EASY/MEDIUM upgrades.
        long activeHardCount = upgrade.getDifficulty() == Difficulty.HARD
                ? repository.countByUserIdAndStatusAndDifficulty(userId, UpgradeStatus.ACTIVE, Difficulty.HARD)
                : 0L;
        schedulingService.validateWithinHardLimit(upgrade.getDifficulty(), activeHardCount);
        upgrade.activate(startDate != null ? startDate : LocalDate.now());
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeActivated(upgrade.getId(), userId, upgrade.getActualStartDate(), LocalDateTime.now()));
        return upgrade;
    }

    /** Pauses an owned ACTIVE upgrade. */
    @Transactional
    public HealthUpgrade pause(UUID userId, UUID id) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        upgrade.pause();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradePaused(upgrade.getId(), userId, LocalDateTime.now()));
        return upgrade;
    }

    /** Completes an owned ACTIVE upgrade. */
    @Transactional
    public HealthUpgrade complete(UUID userId, UUID id) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        upgrade.complete();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeCompleted(upgrade.getId(), userId, LocalDateTime.now()));
        return upgrade;
    }

    /** Abandons an owned upgrade. */
    @Transactional
    public HealthUpgrade abandon(UUID userId, UUID id) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        upgrade.abandon();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeAbandoned(upgrade.getId(), userId, LocalDateTime.now()));
        return upgrade;
    }

    /** Reschedules an owned upgrade to a new date (reactivates an abandoned one to PLANNED). */
    @Transactional
    public HealthUpgrade reschedule(UUID userId, UUID id, LocalDate newDate) {
        HealthUpgrade upgrade = getOwnedUpgrade(userId, id);
        upgrade.reschedule(newDate);
        return repository.save(upgrade);
    }

    // ---- UpgradeQuery (inbound port) ----

    /** {@inheritDoc} */
    @Override
    public HealthUpgrade getOwnedUpgrade(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Upgrade not found: " + id)); // ownership guard
    }

    /** {@inheritDoc} */
    @Override
    public Optional<HealthUpgrade> findOwned(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId); // best-effort, no throw
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
