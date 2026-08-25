package com.healthupgrades.tracking.adapter.out.persistence;

import com.healthupgrades.support.AProgressEntry;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BR-6 — at most one progress entry per upgrade per date — proved against the constraint that actually
 * enforces it.
 *
 * <p>{@code TrackingService} checks for an existing entry before inserting, and that check is what turns
 * an ordinary double submission into a clean 409. It cannot survive a race: two requests can both pass
 * the check before either inserts. The unique index on {@code (upgrade_id, date)} is the backstop, and
 * until now nothing verified it existed — the service test mocks the repository, so it would pass just
 * as happily against a table with no constraint at all.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProgressEntryRepositoryAdapter.class)
class ProgressEntryPersistenceIT extends PostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);

    @Autowired ProgressEntryRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    private UUID userId;
    private UUID upgradeId;

    /** An unsaved entry: the id is left null so the database generates it, as production does. */
    private static ProgressEntry entry(UUID upgradeId, UUID userId, LocalDate date, boolean completed) {
        return AProgressEntry.on(upgradeId, userId, date).id(null).completed(completed).build();
    }

    @BeforeEach
    void createAnUpgradeToLogAgainst() {
        userId = entityManager.persistAndFlush(AUser.aUser().id(null).build()).getId();
        HealthUpgrade upgrade = entityManager.persistAndFlush(
                AnUpgrade.ownedBy(userId).id(null).status(UpgradeStatus.ACTIVE)
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        upgradeId = upgrade.getId();
    }

    @Test
    void GivenADayIsAlreadyLogged_WhenASecondEntryForItIsInserted_ThenTheDatabaseRefusesIt() {
        repository.save(entry(upgradeId, userId, DAY, true));
        entityManager.flush();

        assertThatThrownBy(() -> {
            repository.save(entry(upgradeId, userId, DAY, false));
            entityManager.flush();
        })
                // Named rather than typed: the claim is that *this* index exists and fires, not that
                // some constraint somewhere did. The type is Hibernate's here because the flush is the
                // test's; through a repository in production Spring translates it to
                // DataIntegrityViolationException, which is what DuplicateProgressException shadows.
                .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
                .hasMessageContaining("uq_progress_upgrade_date");
    }

    @Test
    void GivenTheSameDayOnADifferentUpgrade_WhenItIsLogged_ThenItIsAccepted() {
        // The constraint is per upgrade and date, not per date. Logging every active upgrade in one
        // pass is FR-19's whole point, and a constraint on date alone would break it.
        UUID otherUpgradeId = entityManager.persistAndFlush(
                AnUpgrade.ownedBy(userId).id(null).status(UpgradeStatus.ACTIVE)
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();

        repository.save(entry(upgradeId, userId, DAY, true));
        repository.save(entry(otherUpgradeId, userId, DAY, true));
        entityManager.flush();

        assertThat(repository.findByUserIdAndDate(userId, DAY)).hasSize(2);
    }

    @Test
    void GivenTwoDaysOnTheSameUpgrade_WhenBothAreLogged_ThenBothAreAccepted() {
        repository.save(entry(upgradeId, userId, DAY, true));
        repository.save(entry(upgradeId, userId, DAY.minusDays(1), true));
        entityManager.flush();

        assertThat(repository.findByUpgradeIdOrderByDateDesc(upgradeId)).hasSize(2);
    }

    @Test
    void GivenEntriesOnSeveralDays_WhenAnUpgradesHistoryIsRead_ThenItComesBackNewestFirst() {
        // FR-20's ordering, which lives in the query's name rather than in any Java the services run.
        for (int daysAgo = 0; daysAgo < 3; daysAgo++) {
            repository.save(entry(upgradeId, userId, DAY.minusDays(daysAgo), true));
        }
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByUpgradeIdOrderByDateDesc(upgradeId))
                .extracting(ProgressEntry::getDate)
                .containsExactly(DAY, DAY.minusDays(1), DAY.minusDays(2));
    }

    @Test
    void GivenEntriesInsideAndOutsideTheWeek_WhenTheWeekIsQueried_ThenTheRangeIsInclusiveAtBothEnds() {
        // FR-21. The dashboard's weekly rate is computed over exactly what this query returns, so an
        // exclusive bound at either end quietly changes the number the user sees.
        repository.save(entry(upgradeId, userId, DAY, true));
        repository.save(entry(upgradeId, userId, DAY.minusDays(6), true));
        repository.save(entry(upgradeId, userId, DAY.minusDays(7), true));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByUserIdAndDateBetween(userId, DAY.minusDays(6), DAY)).hasSize(2);
    }

    @Test
    void GivenAnotherUsersEntries_WhenADaysProgressIsQueried_ThenTheyAreNotIncluded() {
        UUID strangerId = entityManager.persistAndFlush(
                AUser.aUser().id(null).email("stranger@example.com").build()).getId();
        UUID strangerUpgradeId = entityManager.persistAndFlush(
                AnUpgrade.ownedBy(strangerId).id(null).status(UpgradeStatus.ACTIVE)
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();
        repository.save(entry(strangerUpgradeId, strangerId, DAY, true));
        entityManager.flush();

        assertThat(repository.findByUserIdAndDate(userId, DAY)).isEmpty();
    }
}
