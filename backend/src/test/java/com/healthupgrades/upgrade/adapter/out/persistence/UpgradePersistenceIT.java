package com.healthupgrades.upgrade.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.user.domain.model.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The upgrade invariants that only a real database can decide.
 *
 * <p><strong>BR-14</strong> — concurrent edits are refused rather than silently merged. That is the
 * {@code @Version} column doing its job, and no unit test can see it: a mocked repository accepts both
 * writes and the second one silently wins, which is exactly the outcome the rule exists to prevent. A
 * save that races the deletion of its area is the same lost race, and is refused the same way, because
 * the database moves the version when the delete detaches the upgrade.
 *
 * <p><strong>BR-15</strong> — a user-scoped query returns nothing for another user's row. The services
 * are only as safe as these queries; a {@code findById} slipping in where {@code findByIdAndUserId}
 * belongs would pass every service test, because those stub the repository.
 *
 * <p><strong>FR-14 / BR-13</strong> — deleting an upgrade deletes what was recorded against it,
 * reflections included, and detaches its notifications. The foreign keys decide that, not the code.
 *
 * <p>An {@code *IT}, so it runs under {@code verify} against the container {@link PostgresIT} starts.
 * {@code @AutoConfigureTestDatabase(replace = NONE)} is required: {@code @DataJpaTest} would otherwise
 * swap in an embedded database and quietly test a different engine from the one that ships.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UpgradeRepositoryAdapter.class)
class UpgradePersistenceIT extends PostgresIT {

    @Autowired
    UpgradeRepositoryPort repository;

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

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
                })
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void GivenAnUpgradeChangedUnderAnOpenTransaction_WhenThatTransactionCommits_ThenItIsRefusedAsAConflict() {
        // BR-14 on the path the services take: the entity stays attached, and the write is the flush at
        // the commit of their @Transactional method, after save() has returned. The clash therefore
        // surfaces from the commit, as whatever the transaction manager makes of it, and
        // GlobalExceptionHandler answers 409 only for an OptimisticLockingFailureException; any other
        // type would be a 500. The test's own transaction never commits, so every transaction here is a
        // new one, on an account of its own, which is deleted afterwards along with its upgrade.
        TransactionTemplate committing = new TransactionTemplate(transactionManager);
        committing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UUID editorId = committing.execute(
                status -> persistedUser(UUID.randomUUID() + "@example.com").getId());
        try {
            UUID id = committing.execute(status -> entityManager
                    .persistAndFlush(anUpgradeFor(editorId, UpgradeStatus.ACTIVE))
                    .getId());

            assertThatThrownBy(() -> committing.executeWithoutResult(firstEditor -> {
                        HealthUpgrade first =
                                repository.findByIdAndUserId(id, editorId).orElseThrow();
                        first.updateDetails(null, "Saved by the first editor", null, first.getType(), null, null, null);
                        repository.save(first);

                        committing.executeWithoutResult(secondEditor -> {
                            HealthUpgrade second =
                                    repository.findByIdAndUserId(id, editorId).orElseThrow();
                            second.updateDetails(
                                    null, "Saved by the second editor", null, second.getType(), null, null, null);
                            repository.save(second);
                        });
                    }))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            committing.executeWithoutResult(
                    status -> execute("DELETE FROM users WHERE id = CAST(?1 AS uuid)", editorId));
        }
    }

    @Test
    void GivenAnUpgradeLoadedBeforeItsAreaWasDeleted_WhenItIsSavedAfterwards_ThenItsCommitIsRefusedAsAConflict() {
        // #340, D6.2. Deleting an area detaches its upgrades through ON DELETE SET NULL (area_id), a write
        // Hibernate never sees. Unless that write moves the version, the request that loaded the upgrade
        // first passes the version check at its commit and writes the deleted area's id back, which the
        // area key refuses as a DataIntegrityViolationException: a 500. With the version moved, its update
        // matches no row, and the commit fails as the optimistic-lock conflict GlobalExceptionHandler
        // answers 409. One thread: the delete is a transaction that commits inside the save's, which is the
        // order two requests can take. Every transaction here commits, on an account of its own, which is
        // deleted afterwards with everything it owns.
        TransactionTemplate committing = new TransactionTemplate(transactionManager);
        committing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UUID editorId = committing.execute(
                status -> persistedUser(UUID.randomUUID() + "@example.com").getId());
        try {
            UUID areaId = UUID.randomUUID();
            UUID id = committing.execute(status -> {
                persistArea(areaId, editorId);
                return entityManager
                        .persistAndFlush(AnUpgrade.ownedBy(editorId)
                                .id(null)
                                .areaId(areaId)
                                .status(UpgradeStatus.ACTIVE)
                                .build())
                        .getId();
            });

            assertThatThrownBy(() -> committing.executeWithoutResult(request -> {
                        HealthUpgrade loaded =
                                repository.findByIdAndUserId(id, editorId).orElseThrow();

                        committing.executeWithoutResult(
                                areaDelete -> execute("DELETE FROM health_areas WHERE id = CAST(?1 AS uuid)", areaId));

                        loaded.pause();
                        repository.save(loaded);
                    }))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            committing.executeWithoutResult(
                    status -> execute("DELETE FROM users WHERE id = CAST(?1 AS uuid)", editorId));
        }
    }

    @Test
    void GivenAnUpgradeFiledUnderAnArea_WhenTheAreaIsDeleted_ThenTheUpgradeIsDetachedAndItsVersionMovesOnce() {
        // FR-8: the upgrade survives its area, detached. The version moving is what D6.2 rests on, and it is
        // the referential action's own UPDATE that has to fire the trigger, which only a real delete shows.
        UUID areaId = UUID.randomUUID();
        persistArea(areaId, ownerId);
        UUID id = entityManager
                .persistAndFlush(AnUpgrade.ownedBy(ownerId)
                        .id(null)
                        .areaId(areaId)
                        .status(UpgradeStatus.ACTIVE)
                        .build())
                .getId();
        long before = versionOf(id);

        execute("DELETE FROM health_areas WHERE id = CAST(?1 AS uuid)", areaId);

        assertThat(count("SELECT count(*) FROM health_upgrades WHERE id = CAST(?1 AS uuid) AND area_id IS NULL", id))
                .isEqualTo(1);
        assertThat(versionOf(id)).isEqualTo(before + 1);
    }

    @Test
    void GivenAnUpgradeFiledUnderAnArea_WhenItsOwnerClearsTheArea_ThenItsVersionMovesOnceNotTwice() {
        // An owner's own clear of the area moves the version once. Hibernate's update moves it, and the
        // trigger's WHEN keeps it out of that write; the function would set the same OLD.version + 1 even
        // without the WHEN, so what this pins is the outcome, one move, not the guard.
        UUID areaId = UUID.randomUUID();
        persistArea(areaId, ownerId);
        UUID id = entityManager
                .persistAndFlush(AnUpgrade.ownedBy(ownerId)
                        .id(null)
                        .areaId(areaId)
                        .status(UpgradeStatus.ACTIVE)
                        .build())
                .getId();
        entityManager.clear();
        long before = versionOf(id);

        HealthUpgrade upgrade = repository.findByIdAndUserId(id, ownerId).orElseThrow();
        upgrade.updateDetails(null, upgrade.getTitle(), null, upgrade.getType(), null, null, null);
        repository.save(upgrade);
        entityManager.flush();

        assertThat(versionOf(id)).isEqualTo(before + 1);
    }

    @Test
    void GivenAFreshlyPersistedUpgrade_WhenItIsRead_ThenItCarriesAVersionToCompareAgainst() {
        // The precondition BR-14 rests on: no version column, no conflict detection.
        UUID id = persistedUpgrade().getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdAndUserId(id, ownerId).orElseThrow().getVersion())
                .isNotNull();
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
        entityManager.persistAndFlush(AnUpgrade.ownedBy(strangerId)
                .id(null)
                .status(UpgradeStatus.ACTIVE)
                .build());
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

        assertThat(repository.findByUserIdAndStatus(ownerId, UpgradeStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    void GivenUpgradesOfTwoUsersInSeveralStates_WhenOneStatusIsCountedByOwner_ThenEachOwnerGetsOnlyTheirOwn() {
        // The check-in nudge's read (D2.14): a count per owner, not the upgrades themselves. Other ITs
        // commit upgrades into the shared database, so only these two owners' counts are asserted.
        entityManager.persistAndFlush(anUpgradeFor(ownerId, UpgradeStatus.ACTIVE));
        entityManager.persistAndFlush(anUpgradeFor(ownerId, UpgradeStatus.ACTIVE));
        entityManager.persistAndFlush(anUpgradeFor(ownerId, UpgradeStatus.PLANNED));
        entityManager.persistAndFlush(anUpgradeFor(strangerId, UpgradeStatus.ACTIVE));
        entityManager.clear();

        Map<UUID, Long> active = repository.countByOwner(UpgradeStatus.ACTIVE);

        assertThat(active).containsEntry(ownerId, 2L).containsEntry(strangerId, 1L);
    }

    @Test
    void GivenUpgradesEitherSideOfTheirTargetDate_WhenTheOverdueCandidatesAreRead_ThenOnlyActiveOnesPastItComeBack() {
        // D3.15: the overdue sweep's query. Other ITs commit upgrades into the shared database, so only
        // these rows are asserted on.
        LocalDate today = LocalDate.of(2026, 3, 15);
        UUID late = persisted(ownerId, UpgradeStatus.ACTIVE, today.minusDays(1));
        UUID dueToday = persisted(ownerId, UpgradeStatus.ACTIVE, today);
        UUID ahead = persisted(strangerId, UpgradeStatus.ACTIVE, today.plusDays(1));
        UUID pausedAndLate = persisted(ownerId, UpgradeStatus.PAUSED, today.minusDays(1));
        UUID noTarget = persisted(strangerId, UpgradeStatus.ACTIVE, null);
        UUID strangersLate = persisted(strangerId, UpgradeStatus.ACTIVE, today.minusDays(30));
        entityManager.clear();

        List<UUID> found = repository.findActivePastTargetDate(today).stream()
                .map(HealthUpgrade::getId)
                .toList();

        assertThat(found).contains(late, strangersLate).doesNotContain(dueToday, ahead, pausedAndLate, noTarget);
    }

    @Test
    void GivenAnUpgradeCompletedAtAMoment_WhenItIsReadBack_ThenTheMomentIsTheOneRecorded() {
        // FR-27, ADR-080: the column V19 adds, mapped, and the stamp surviving the round trip unchanged.
        UUID id = persistedUpgrade().getId();
        entityManager.flush();
        entityManager.clear();
        HealthUpgrade upgrade = repository.findByIdAndUserId(id, ownerId).orElseThrow();
        LocalDateTime at = LocalDateTime.of(2026, 3, 20, 18, 45, 30);
        upgrade.complete(at);
        repository.save(upgrade);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdAndUserId(id, ownerId).orElseThrow().getCompletedAt())
                .isEqualTo(at);
    }

    @Test
    void GivenActiveHardUpgradesAcrossUsers_WhenOneUsersAreCounted_ThenTheOthersDoNotInflateTheCount() {
        // The count behind BR-5. Counting across users would refuse a user their first HARD upgrade
        // because somebody else already has three.
        entityManager.persistAndFlush(hardActiveFor(strangerId));
        entityManager.persistAndFlush(hardActiveFor(strangerId));
        entityManager.persistAndFlush(hardActiveFor(strangerId));
        entityManager.clear();

        assertThat(repository.countActiveHardUpgradesForLimitCheck(ownerId)).isZero();
    }

    @Test
    void GivenAnUpgradeWithEverythingRecordedAgainstIt_WhenItIsDeleted_ThenItsRecordsGoWithItButItsNotificationsStay() {
        // FR-14 and BR-13 meet here. The cascade is declared by the foreign keys, not by any entity
        // mapping, so a service test that stubs the repository cannot see it. Notifications are the
        // exception: they belong to the user's history, not to the upgrade, and are detached instead.
        UUID id = persistedUpgrade().getId();
        UUID notificationId = UUID.randomUUID();
        execute("INSERT INTO tracking_configs (upgrade_id, tracking_type) VALUES (CAST(?1 AS uuid), 'BOOLEAN')", id);
        execute(
                "INSERT INTO progress_entries (upgrade_id, user_id, date) "
                        + "VALUES (CAST(?1 AS uuid), CAST(?2 AS uuid), DATE '2026-03-02')",
                id,
                ownerId);
        execute(
                "INSERT INTO reminders (upgrade_id, reminder_time, enabled) "
                        + "VALUES (CAST(?1 AS uuid), TIME '09:00', TRUE)",
                id);
        execute(
                "INSERT INTO reflections (upgrade_id, user_id, date) "
                        + "VALUES (CAST(?1 AS uuid), CAST(?2 AS uuid), DATE '2026-03-02')",
                id,
                ownerId);
        execute(
                "INSERT INTO notifications (id, user_id, type, category, title, related_upgrade_id) "
                        + "VALUES (CAST(?1 AS uuid), CAST(?2 AS uuid), 'UPGRADE_CREATED', 'INFO', 'New upgrade idea', "
                        + "CAST(?3 AS uuid))",
                notificationId,
                ownerId,
                id);
        entityManager.clear();

        repository.delete(repository.findByIdAndUserId(id, ownerId).orElseThrow());
        entityManager.flush();

        assertThat(count("SELECT count(*) FROM tracking_configs WHERE upgrade_id = CAST(?1 AS uuid)", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM progress_entries WHERE upgrade_id = CAST(?1 AS uuid)", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM reminders WHERE upgrade_id = CAST(?1 AS uuid)", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM reflections WHERE upgrade_id = CAST(?1 AS uuid)", id))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM notifications "
                                + "WHERE id = CAST(?1 AS uuid) AND related_upgrade_id IS NULL",
                        notificationId))
                .isEqualTo(1);
    }

    private void execute(String sql, Object... uuids) {
        var query = entityManager.getEntityManager().createNativeQuery(sql);
        for (int i = 0; i < uuids.length; i++) {
            query.setParameter(i + 1, uuids[i].toString());
        }
        query.executeUpdate();
    }

    private long count(String sql, UUID id) {
        return ((Number) entityManager
                        .getEntityManager()
                        .createNativeQuery(sql)
                        .setParameter(1, id.toString())
                        .getSingleResult())
                .longValue();
    }

    private long versionOf(UUID id) {
        return count("SELECT version FROM health_upgrades WHERE id = CAST(?1 AS uuid)", id);
    }

    private void persistArea(UUID areaId, UUID userId) {
        execute(
                "INSERT INTO health_areas (id, user_id, name) VALUES (CAST(?1 AS uuid), CAST(?2 AS uuid), 'Sleep')",
                areaId,
                userId);
    }

    private User persistedUser(String email) {
        return entityManager.persistAndFlush(AUser.aUser().id(null).email(email).build());
    }

    private HealthUpgrade persistedUpgrade() {
        return entityManager.persistAndFlush(anUpgradeFor(ownerId, UpgradeStatus.ACTIVE));
    }

    private static HealthUpgrade anUpgradeFor(UUID userId, UpgradeStatus status) {
        return AnUpgrade.ownedBy(userId)
                .id(null)
                .status(status)
                .plannedStartDate(LocalDate.of(2026, 3, 1))
                .build();
    }

    private UUID persisted(UUID userId, UpgradeStatus status, LocalDate targetEndDate) {
        return entityManager
                .persistAndFlush(AnUpgrade.ownedBy(userId)
                        .id(null)
                        .status(status)
                        .targetEndDate(targetEndDate)
                        .build())
                .getId();
    }

    private static HealthUpgrade hardActiveFor(UUID userId) {
        return AnUpgrade.ownedBy(userId)
                .id(null)
                .status(UpgradeStatus.ACTIVE)
                .difficulty(Difficulty.HARD)
                .build();
    }
}
