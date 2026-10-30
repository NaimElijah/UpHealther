package com.healthupgrades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.PostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner a child row names is its parent's owner, held by the schema rather than by the one writer that
 * stamps it (ADR-073).
 *
 * <p>A progress entry and a reflection carry a {@code user_id} that the owner-scoped reads filter on (BR-15),
 * and an upgrade may be filed only under its owner's own area (BR-18). The services stamp both correctly;
 * these cases write past them, as a manual fix or a later migration would, and expect the composite foreign
 * key to refuse a row whose owner differs. Each test rolls back, so the rows it writes are its own, but for
 * the one that needs a committed row, which deletes what it commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OwnerConsistencyIT extends PostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();
    private final UUID ownersAreaId = UUID.randomUUID();
    private final UUID strangersAreaId = UUID.randomUUID();

    @BeforeEach
    void anOwnerAndAStrangerEachWithAnArea() {
        insertUser(ownerId);
        insertUser(strangerId);
        jdbc.update("INSERT INTO health_areas (id, user_id, name) VALUES (?, ?, 'Sleep')", ownersAreaId, ownerId);
        jdbc.update("INSERT INTO health_areas (id, user_id, name) VALUES (?, ?, 'Sleep')", strangersAreaId, strangerId);
        jdbc.update(
                "INSERT INTO health_upgrades (id, user_id, area_id, title, type) VALUES (?, ?, ?, 'Walk', 'HABIT')",
                upgradeId,
                ownerId,
                ownersAreaId);
    }

    @Test
    void GivenAnUpgrade_WhenAProgressEntryNamesAnotherUserAsItsOwner_ThenTheDatabaseRefusesIt() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO progress_entries (upgrade_id, user_id, date) VALUES (?, ?, DATE '2026-03-02')",
                        upgradeId,
                        strangerId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("progress_entries_upgrade_owner_fkey");
    }

    @Test
    void GivenAnUpgrade_WhenAReflectionNamesAnotherUserAsItsOwner_ThenTheDatabaseRefusesIt() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO reflections (upgrade_id, user_id, date) VALUES (?, ?, DATE '2026-03-02')",
                        upgradeId,
                        strangerId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reflections_upgrade_owner_fkey");
    }

    @Test
    void GivenAnUpgrade_WhenItIsFiledUnderAnotherUsersArea_ThenTheDatabaseRefusesIt() {
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE health_upgrades SET area_id = ? WHERE id = ?", strangersAreaId, upgradeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("health_upgrades_area_owner_fkey");
    }

    @Test
    void GivenRowsThatNameTheUpgradesOwner_WhenTheyAreWritten_ThenEachIsAccepted() {
        jdbc.update(
                "INSERT INTO progress_entries (upgrade_id, user_id, date) VALUES (?, ?, DATE '2026-03-02')",
                upgradeId,
                ownerId);
        jdbc.update(
                "INSERT INTO reflections (upgrade_id, user_id, date) VALUES (?, ?, DATE '2026-03-02')",
                upgradeId,
                ownerId);
        jdbc.update("UPDATE health_upgrades SET area_id = NULL WHERE id = ?", upgradeId);
        jdbc.update("UPDATE health_upgrades SET area_id = ? WHERE id = ?", ownersAreaId, upgradeId);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM progress_entries WHERE upgrade_id = ?", Long.class, upgradeId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reflections WHERE upgrade_id = ?", Long.class, upgradeId))
                .isEqualTo(1L);
    }

    @Test
    void GivenAnUpgradeFiledUnderAnArea_WhenTheAreaIsDeleted_ThenOnlyTheAreaIsClearedAndTheOwnerStays() {
        // FR-8. SET NULL (area_id), not SET NULL: a plain one would null user_id too, which is NOT NULL,
        // and the delete would fail.
        jdbc.update("DELETE FROM health_areas WHERE id = ?", ownersAreaId);

        assertThat(jdbc.queryForObject(
                        "SELECT area_id IS NULL AND user_id = ? FROM health_upgrades WHERE id = ?",
                        Boolean.class,
                        ownerId,
                        upgradeId))
                .isTrue();
    }

    @Test
    void GivenAnUpgradeFiledUnderAnArea_WhenTheAreaIsDeleted_ThenItsVersionMovesExactlyOnce() {
        // V14's trigger moves the version for the detach. V18 put a second key with its own SET NULL beside
        // V13's, and both match this row; the second to run finds area_id already NULL, so the version
        // moves once, not twice.
        long before = versionOf(upgradeId);

        jdbc.update("DELETE FROM health_areas WHERE id = ?", ownersAreaId);

        assertThat(versionOf(upgradeId)).isEqualTo(before + 1);
    }

    @Test
    void
            GivenAnotherUsersRowFiledUnderTheAreaBeforeBr18_WhenTheAreaIsDeleted_ThenItIsDetachedTooAndItsVersionMovesOnce() {
        // FR-8 for every upgrade under the area, not only its owner's. V13's composite key matches
        // (area_id, user_id), so its SET NULL never reaches a row of another user, and that row was left
        // naming an area with no row behind it. V18's single-column key matches area_id alone. The legacy
        // row is written with the foreign-key triggers off, which needs the container's superuser and lasts
        // only for this transaction, and they are back on before the delete.
        UUID legacyId = UUID.randomUUID();
        jdbc.execute("SET LOCAL session_replication_role = replica");
        jdbc.update(
                "INSERT INTO health_upgrades (id, user_id, area_id, title, type) VALUES (?, ?, ?, 'Old', 'HABIT')",
                legacyId,
                strangerId,
                ownersAreaId);
        jdbc.execute("SET LOCAL session_replication_role = DEFAULT");
        long before = versionOf(legacyId);

        jdbc.update("DELETE FROM health_areas WHERE id = ?", ownersAreaId);

        assertThat(jdbc.queryForObject(
                        "SELECT area_id IS NULL AND user_id = ? FROM health_upgrades WHERE id = ?",
                        Boolean.class,
                        strangerId,
                        legacyId))
                .as("the other user's row is detached, and keeps its owner")
                .isTrue();
        assertThat(versionOf(legacyId)).isEqualTo(before + 1);
    }

    @Test
    void GivenARowFiledUnderAForeignAreaBeforeBr18_WhenAnotherFieldIsEdited_ThenTheEditIsAccepted() {
        // UpgradeService.update checks only a changed area, so such a row stays editable, and the key is
        // left NOT VALID for the same reason. PostgreSQL skips the check on an update that leaves the key
        // as it was, but not on a row the same transaction inserted, so the legacy row is committed first,
        // as it would have been long before, and edited in a transaction of its own. Foreign-key triggers
        // are off while it is written, which needs the container's superuser and lasts only for that
        // transaction. Everything committed here is owned by two accounts of its own, deleted afterwards.
        TransactionTemplate committing = new TransactionTemplate(transactionManager);
        committing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UUID legacyOwnerId = UUID.randomUUID();
        UUID areaOwnerId = UUID.randomUUID();
        UUID foreignAreaId = UUID.randomUUID();
        UUID legacyId = UUID.randomUUID();
        try {
            committing.executeWithoutResult(status -> {
                insertUser(legacyOwnerId);
                insertUser(areaOwnerId);
                jdbc.update(
                        "INSERT INTO health_areas (id, user_id, name) VALUES (?, ?, 'Sleep')",
                        foreignAreaId,
                        areaOwnerId);
                jdbc.execute("SET LOCAL session_replication_role = replica");
                jdbc.update(
                        "INSERT INTO health_upgrades (id, user_id, area_id, title, type) "
                                + "VALUES (?, ?, ?, 'Old', 'HABIT')",
                        legacyId,
                        legacyOwnerId,
                        foreignAreaId);
            });

            committing.executeWithoutResult(status -> jdbc.update(
                    "UPDATE health_upgrades SET title = 'Renamed', version = version + 1 WHERE id = ?", legacyId));

            assertThat(jdbc.queryForObject("SELECT title FROM health_upgrades WHERE id = ?", String.class, legacyId))
                    .isEqualTo("Renamed");
            assertThat(jdbc.queryForObject(
                            "SELECT convalidated FROM pg_constraint WHERE conname = 'health_upgrades_area_owner_fkey'",
                            Boolean.class))
                    .as("left NOT VALID, so a database holding such a row still migrates")
                    .isFalse();
        } finally {
            committing.executeWithoutResult(
                    status -> jdbc.update("DELETE FROM users WHERE id IN (?, ?)", legacyOwnerId, areaOwnerId));
        }
    }

    private long versionOf(UUID id) {
        Long version = jdbc.queryForObject("SELECT version FROM health_upgrades WHERE id = ?", Long.class, id);
        assertThat(version).as("the upgrade %s exists", id).isNotNull();
        return version;
    }

    private void insertUser(UUID userId) {
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash) VALUES (?, 'Someone', ?, 'x')",
                userId,
                "owner-consistency-" + userId + "@example.com");
    }
}
