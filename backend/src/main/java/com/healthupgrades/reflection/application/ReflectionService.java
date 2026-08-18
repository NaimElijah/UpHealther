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

/**
 * Use cases for reflections: write one against an upgrade, and read an upgrade's history of them.
 *
 * <p>Ownership of the upgrade is confirmed through the upgrade context's inbound {@link UpgradeQuery}
 * port before either operation, so a reflection can never be filed against somebody else's upgrade.
 */
@Service
@RequiredArgsConstructor
public class ReflectionService {

    private final ReflectionRepositoryPort repository;
    private final UpgradeQuery upgradeQuery;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock; // decides the date a reflection defaults to

    /**
     * Writes a reflection against an owned upgrade and announces it.
     *
     * <p>Unlike a progress entry there is no one-per-day rule: a user may reflect as often as they like,
     * including several times about the same day.
     *
     * @param userId    the author, who must own the upgrade
     * @param upgradeId the upgrade being reflected on
     * @param details   the reflection; a null date means today by the injected clock
     * @return the persisted reflection
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if the upgrade does
     *         not exist or belongs to somebody else
     */
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

    /**
     * Reads an owned upgrade's reflections.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade to read
     * @return its reflections, newest first; empty when none have been written
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if the upgrade does
     *         not exist or belongs to somebody else
     */
    public List<Reflection> getForUpgrade(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return repository.findByUpgradeIdOrderByDateDesc(upgradeId);
    }
}
