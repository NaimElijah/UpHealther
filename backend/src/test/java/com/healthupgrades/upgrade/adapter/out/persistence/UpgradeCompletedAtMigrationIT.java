package com.healthupgrades.upgrade.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.PostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

/**
 * What {@code V19__upgrade_completed_at.sql} and {@code V20__upgrade_completed_at_follows_status.sql} do to
 * the upgrades they find (FR-27, ADR-080).
 *
 * <p>Every other IT migrates an empty database straight to the latest version, so no other one sees an
 * upgrade written before V19. This one migrates a database of its own to V18, writes a completed and an
 * active upgrade as V18 stored them, and then runs V19 over them, as {@code AuthSessionMigrationIT} does.
 *
 * <p>The backfill is approximate by design: a row completed before V19 has no record of when, so it takes
 * its {@code updated_at}, and a row that never completed takes nothing. V20's CHECK then validates over
 * exactly those rows, and refuses to run over a COMPLETED row that has no stamp.
 */
class UpgradeCompletedAtMigrationIT extends PostgresIT {

    /** The account {@code V2__seed_data.sql} creates, which a V18 upgrade must belong to. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private static final LocalDateTime LAST_EDITED = LocalDateTime.of(2026, 2, 14, 9, 30);

    /** The constraint V20 adds, which a refused validation names. */
    private static final String COMPLETED_AT_CHECK = "health_upgrades_completed_at_check";

    @Test
    void GivenUpgradesStoredUnderV18_WhenV19AndV20Run_ThenOnlyTheCompletedOneTakesItsLastEditAndTheCheckHolds()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("v19_backfills_completed_at");
        migrate(database, "18");
        UUID completed = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        try (Connection connection = database.connect()) {
            insertV18Upgrade(connection, completed, "COMPLETED");
            insertV18Upgrade(connection, active, "ACTIVE");
        }

        migrate(database, "19");

        try (Connection connection = database.connect()) {
            assertThat(completedAtOf(connection, completed))
                    .as("the closest record of when it was completed is when it was last written")
                    .isEqualTo(LAST_EDITED);
            assertThat(completedAtOf(connection, active))
                    .as("an upgrade that never completed has no completion")
                    .isNull();
        }

        migrate(database, "20");

        try (Connection connection = database.connect()) {
            assertThat(completedAtOf(connection, completed))
                    .as("V20's CHECK validated over the backfilled row, which keeps its copied stamp")
                    .isEqualTo(LAST_EDITED);
            assertThat(completedAtOf(connection, active))
                    .as("and over the row that never completed, which still has none")
                    .isNull();
            assertThat(isValidated(connection, COMPLETED_AT_CHECK))
                    .as("the constraint is validated, not left NOT VALID")
                    .isTrue();
        }
    }

    @Test
    void GivenACompletedUpgradeWithNoStampBeforeV20_WhenV20Runs_ThenItsValidationRefusesTheMigration()
            throws SQLException {
        // A row outside the rule fails the VALIDATE, and so the start, rather than being changed silently:
        // what its completion time should be is a decision about someone's data (ADR-073).
        IsolatedDatabase database = createIsolatedDatabase("v20_refuses_an_unstamped_completion");
        migrate(database, "19");
        try (Connection connection = database.connect()) {
            // Written after V19's backfill, as a stray write would, so its completed_at stays null.
            insertV18Upgrade(connection, UUID.randomUUID(), "COMPLETED");
        }

        assertThatThrownBy(() -> migrate(database, "20"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(COMPLETED_AT_CHECK);
    }

    private static void migrate(IsolatedDatabase database, String version) {
        Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .target(version)
                .load()
                .migrate();
    }

    /** An upgrade as V18 stored one, owned by the seeded account and last written at {@link #LAST_EDITED}. */
    private static void insertV18Upgrade(Connection connection, UUID id, String status) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO health_upgrades (id, user_id, title, type, status, created_at, updated_at)
                VALUES (?, ?, 'Walk daily', 'HABIT', ?, ?, ?)
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, SEEDED_ACCOUNT);
            insert.setString(3, status);
            insert.setTimestamp(4, Timestamp.valueOf(LAST_EDITED.minusDays(30)));
            insert.setTimestamp(5, Timestamp.valueOf(LAST_EDITED));
            insert.executeUpdate();
        }
    }

    private static boolean isValidated(Connection connection, String constraint) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT convalidated FROM pg_constraint WHERE conname = ?")) {
            select.setString(1, constraint);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("V20 added %s", constraint).isTrue();
                return row.getBoolean("convalidated");
            }
        }
    }

    private static LocalDateTime completedAtOf(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT completed_at FROM health_upgrades WHERE id = ?")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("the upgrade survives the migration").isTrue();
                return row.getObject("completed_at", LocalDateTime.class);
            }
        }
    }
}
