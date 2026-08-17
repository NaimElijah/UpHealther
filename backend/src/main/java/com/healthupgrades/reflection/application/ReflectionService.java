package com.healthupgrades.reflection.application;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.reflection.domain.event.ReflectionAdded;
import com.healthupgrades.reflection.application.port.in.ReflectionDetails;
import com.healthupgrades.reflection.domain.model.Reflection;
import com.healthupgrades.reflection.domain.port.out.ReflectionRepositoryPort;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
    private final Clock clock; // decides the date a reflection defaults to

    /** Records a reflection against an owned upgrade, defaulting the date to today. */
    @Transactional
    public Reflection create(UUID userId, UUID upgradeId, ReflectionDetails details) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        Reflection reflection = Reflection.builder()
                .upgradeId(upgradeId)
                .userId(userId)
                .date(details.date() != null ? details.date() : LocalDate.now(clock))
                .difficultyRating(details.difficultyRating())
                .benefitRating(details.benefitRating())
                .whatWorked(details.whatWorked())
                .whatDidNotWork(details.whatDidNotWork())
                .nextAdjustment(details.nextAdjustment())
                .build();
        reflection = repository.save(reflection);
        eventPublisher.publish(new ReflectionAdded(reflection.getId(), upgradeId, userId, LocalDateTime.now()));
        return reflection;
    }

    /** An owned upgrade's reflections, newest first. */
    public List<Reflection> getForUpgrade(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return repository.findByUpgradeIdOrderByDateDesc(upgradeId);
    }
}
