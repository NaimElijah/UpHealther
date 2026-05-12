package com.healthupgrades.upgrade.application;

import com.healthupgrades.common.events.*;
import com.healthupgrades.common.exception.ResourceNotFoundException;
import com.healthupgrades.upgrade.api.UpgradeDto;
import com.healthupgrades.upgrade.api.UpgradeRequest;
import com.healthupgrades.upgrade.domain.*;
import com.healthupgrades.upgrade.infrastructure.UpgradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class UpgradeService {

    private final UpgradeRepository repository;
    private final UpgradeSchedulingService schedulingService;
    private final DomainEventPublisher eventPublisher;

    @Transactional
    public UpgradeDto create(UUID userId, UpgradeRequest req) {
        HealthUpgrade upgrade = HealthUpgrade.builder()
                .userId(userId)
                .areaId(req.areaId())
                .title(req.title())
                .description(req.description())
                .type(req.type())
                .status(UpgradeStatus.DRAFT)
                .difficulty(req.difficulty())
                .plannedStartDate(req.plannedStartDate())
                .targetEndDate(req.targetEndDate())
                .motivation(req.motivation())
                .successCriteria(req.successCriteria())
                .build();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeCreated(upgrade.getId(), userId, upgrade.getTitle(), LocalDateTime.now()));
        return toDto(upgrade);
    }

    public List<UpgradeDto> findAll(UUID userId, UpgradeStatus status, UpgradeType type, UUID areaId, Difficulty difficulty) {
        Stream<HealthUpgrade> stream;
        if (status != null) {
            stream = repository.findByUserIdAndStatus(userId, status).stream();
        } else if (type != null) {
            stream = repository.findByUserIdAndType(userId, type).stream();
        } else if (areaId != null) {
            stream = repository.findByUserIdAndAreaId(userId, areaId).stream();
        } else if (difficulty != null) {
            stream = repository.findByUserIdAndDifficulty(userId, difficulty).stream();
        } else {
            stream = repository.findByUserId(userId).stream();
        }
        return stream.map(this::toDto).toList();
    }

    public UpgradeDto findById(UUID userId, UUID id) {
        return toDto(getUpgrade(userId, id));
    }

    @Transactional
    public UpgradeDto update(UUID userId, UUID id, UpgradeRequest req) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        upgrade.setAreaId(req.areaId());
        upgrade.setTitle(req.title());
        upgrade.setDescription(req.description());
        upgrade.setType(req.type());
        upgrade.setTargetEndDate(req.targetEndDate());
        upgrade.setMotivation(req.motivation());
        upgrade.setSuccessCriteria(req.successCriteria());
        if (req.difficulty() != null) upgrade.changeDifficulty(req.difficulty());
        return toDto(repository.save(upgrade));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        repository.delete(upgrade);
    }

    @Transactional
    public UpgradeDto plan(UUID userId, UUID id, LocalDate plannedStartDate) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        upgrade.plan(plannedStartDate);
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradePlanned(upgrade.getId(), userId, plannedStartDate, LocalDateTime.now()));
        return toDto(upgrade);
    }

    @Transactional
    public UpgradeDto activate(UUID userId, UUID id, LocalDate startDate) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        schedulingService.validateCanActivate(userId, upgrade.getDifficulty());
        upgrade.activate(startDate != null ? startDate : LocalDate.now());
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeActivated(upgrade.getId(), userId, upgrade.getActualStartDate(), LocalDateTime.now()));
        return toDto(upgrade);
    }

    @Transactional
    public UpgradeDto pause(UUID userId, UUID id) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        upgrade.pause();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradePaused(upgrade.getId(), userId, LocalDateTime.now()));
        return toDto(upgrade);
    }

    @Transactional
    public UpgradeDto complete(UUID userId, UUID id) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        upgrade.complete();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeCompleted(upgrade.getId(), userId, LocalDateTime.now()));
        return toDto(upgrade);
    }

    @Transactional
    public UpgradeDto abandon(UUID userId, UUID id) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        upgrade.abandon();
        upgrade = repository.save(upgrade);
        eventPublisher.publish(new HealthUpgradeAbandoned(upgrade.getId(), userId, LocalDateTime.now()));
        return toDto(upgrade);
    }

    @Transactional
    public UpgradeDto reschedule(UUID userId, UUID id, LocalDate newDate) {
        HealthUpgrade upgrade = getUpgrade(userId, id);
        upgrade.reschedule(newDate);
        return toDto(repository.save(upgrade));
    }

    public HealthUpgrade getUpgrade(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Upgrade not found: " + id));
    }

    public UpgradeDto toDto(HealthUpgrade u) {
        return new UpgradeDto(u.getId(), u.getUserId(), u.getAreaId(), u.getTitle(),
                u.getDescription(), u.getType(), u.getStatus(), u.getDifficulty(),
                u.getPlannedStartDate(), u.getActualStartDate(), u.getTargetEndDate(),
                u.getMotivation(), u.getSuccessCriteria(), u.isOverdue(),
                u.getCreatedAt(), u.getUpdatedAt());
    }
}
