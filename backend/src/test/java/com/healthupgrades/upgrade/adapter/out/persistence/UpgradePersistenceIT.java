package com.healthupgrades.upgrade.adapter.out.persistence;

import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upgrade invariants that only a real database can decide.
 *
 * <p><strong>BR-14</strong> — concurrent edits are refused rather than silently merged. That is the
 * {@code @Version} column doing its job, and no unit test can see it: a mocked repository accepts both
 * writes and the second one silently wins, which is exactly the outcome the rule exists to prevent.
 *
 * <p><strong>BR-15</strong> — a user-scoped query returns nothing for another user's row. The services
 * are only as safe as these queries; a {@code findById} slipping in where {@code findByIdAndUserId}
 * belongs would pass every service test, because those stub the repository.
 *
 * <p>An {@code *IT}, so it runs under {@code verify} against the container {@link PostgresIT} starts.
 * {@code @AutoConfigureTestDatabase(replace = NONE)} is required: {@code @DataJpaTest} would otherwise
 * swap in an embedded database and quietly test a different engine from the one that ships.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UpgradeRepositoryAdapter.class)
class UpgradePersistenceIT extends PostgresIT {

    @Autowired UpgradeRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    private UUID ownerId;
    private UUID strangerId;

    @BeforeEach
    void createTwoUsers() {
        ownerId = persistedUser("owner@example.com").getId();
        strangerId = persistedUser("stranger@example.com").getId();
    }

    @Test
    void GivenTwoLoadsOfTheSameUpgrade_WhenBothAreSaved_ThenTheSecondIsRefusedAsAConflict() {
        // BR-14. Two browser tabs, or one request retried. Without the version check the later write
        // silently discards the earlier one and the user never learns their edit was lost.
        UUID id = persistedUpgrade().getId();
        entityManager.flush();
        entityManager.clear();

        HealthUpgrade first = repository.findByIdAndUserId(id, ownerId).orElseThrow();
        entityManager.detach(first);
        HealthUpgrade second = repository.findByIdAndUserId(id, ownerId).orElseThrow();

        second.updateDetails(null, "Saved by the second editor", null, second.getType(), null, null, null);
        repository.save(second);
        entityManager.flush();

        first.updateDetails(null, "Saved by the first editor", null, first.getType(), null, null, null);

        assertThatThrownBy(() -> {
            repository.save(first);
            entityManager.flush();
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void GivenAFreshlyPersistedUpgrade_WhenItIsRead_ThenItCarriesAVersionToCompareAgainst() {
        // The precondition BR-14 rests on: no version column, no conflict detection.
        UUID id = persistedUpgrade().getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdAndUserId(id, ownerId).orElseThrow().getVersion()).isNotNull();
    }

    @Test
    void GivenAnUpgradeOwnedByOneUser_WhenAnotherUserLooksItUpById_ThenNothingComesBack() {
        // BR-15 at the query, which is where user scoping actually lives in this application.
        UUID id = persistedUpgrade().getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdAndUserId(id, strangerId)).isEmpty();
        assertThat(repository.findByIdAndUserId(id, ownerId)).isPresent();
    }

    @Test
    void GivenUpgradesBelongingToTwoUsers_WhenOneListsTheirs_ThenTheOthersAreNotIncluded() {
        persistedUpgrade();
        entityManager.persistAndFlush(AnUpgrade.ownedBy(strangerId).id(null).status(UpgradeStatus.ACTIVE)
                .createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now()).build());
        entityManager.clear();

        assertThat(repository.findByUserId(ownerId)).hasSize(1);
        assertThat(repository.findByUserId(strangerId)).hasSize(1);
    }

    @Test
    void GivenUpgradesInSeveralStates_WhenOneStatusIsQueried_ThenOnlyThatUsersMatchingOnesComeBack() {
        entityManager.persistAndFlush(anUpgradeFor(ownerId, UpgradeStatus.ACTIVE));
        entityManager.persistAndFlush(anUpgradeFor(ownerId, UpgradeStatus.PLANNED));
        entityManager.persistAndFlush(anUpgradeFor(strangerId, UpgradeStatus.ACTIVE));
        entityManager.clear();

        assertThat(repository.findByUserIdAndStatus(ownerId, UpgradeStatus.ACTIVE)).hasSize(1);
    }

    @Test
    void GivenActiveHardUpgradesAcrossUsers_WhenOneUsersAreCounted_ThenTheOthersDoNotInflateTheCount() {
        // The count behind BR-5. Counting across users would refuse a user their first HARD upgrade
        // because somebody else already has three.
        entityManager.persistAndFlush(hardActiveFor(strangerId));
        entityManager.persistAndFlush(hardActiveFor(strangerId));
        entityManager.persistAndFlush(hardActiveFor(strangerId));
        entityManager.clear();

        assertThat(repository.countByUserIdAndStatusAndDifficulty(
                ownerId, UpgradeStatus.ACTIVE, Difficulty.HARD)).isZero();
    }

    private User persistedUser(String email) {
        return entityManager.persistAndFlush(AUser.aUser().id(null).email(email).build());
    }

    private HealthUpgrade persistedUpgrade() {
        return entityManager.persistAndFlush(anUpgradeFor(ownerId, UpgradeStatus.ACTIVE));
    }

    private static HealthUpgrade anUpgradeFor(UUID userId, UpgradeStatus status) {
        return AnUpgrade.ownedBy(userId).id(null).status(status)
                .plannedStartDate(LocalDate.of(2026, 3, 1))
                .createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now())
                .build();
    }

    private static HealthUpgrade hardActiveFor(UUID userId) {
        return AnUpgrade.ownedBy(userId).id(null).status(UpgradeStatus.ACTIVE).difficulty(Difficulty.HARD)
                .createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now())
                .build();
    }
}
