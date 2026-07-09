package com.healthupgrades.reflection.application;

import com.healthupgrades.common.events.DomainEventPublisher;
import com.healthupgrades.common.events.ReflectionAdded;
import com.healthupgrades.reflection.api.ReflectionDto;
import com.healthupgrades.reflection.api.ReflectionRequest;
import com.healthupgrades.reflection.domain.Reflection;
import com.healthupgrades.reflection.domain.port.out.ReflectionRepositoryPort;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReflectionService {

    private final ReflectionRepositoryPort repository;
    private final UpgradeQuery upgradeQuery;
    private final DomainEventPublisher eventPublisher;

    @Transactional
    public ReflectionDto create(UUID userId, UUID upgradeId, ReflectionRequest req) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        Reflection reflection = Reflection.builder()
                .upgradeId(upgradeId)
                .userId(userId)
                .date(req.date() != null ? req.date() : LocalDate.now())
                .difficultyRating(req.difficultyRating())
                .benefitRating(req.benefitRating())
                .whatWorked(req.whatWorked())
                .whatDidNotWork(req.whatDidNotWork())
                .nextAdjustment(req.nextAdjustment())
                .build();
        reflection = repository.save(reflection);
        eventPublisher.publish(new ReflectionAdded(reflection.getId(), upgradeId, userId, LocalDateTime.now()));
        return toDto(reflection);
    }

    public List<ReflectionDto> getForUpgrade(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return repository.findByUpgradeIdOrderByDateDesc(upgradeId).stream().map(this::toDto).toList();
    }

    private ReflectionDto toDto(Reflection r) {
        return new ReflectionDto(r.getId(), r.getUpgradeId(), r.getUserId(), r.getDate(),
                r.getDifficultyRating(), r.getBenefitRating(), r.getWhatWorked(),
                r.getWhatDidNotWork(), r.getNextAdjustment(), r.getCreatedAt());
    }
}
