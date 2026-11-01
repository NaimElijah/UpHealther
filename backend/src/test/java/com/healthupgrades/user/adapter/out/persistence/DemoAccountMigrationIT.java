package com.healthupgrades.user.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.healthupgrades.support.PostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Where the demo account exists, and where it does not (#84).
 *
 * <p>V2 seeded the account, whose password this repository publishes, into every database, production
 * included. {@code V21__delete_the_seeded_demo_account.sql} deletes it, with everything it owns, and only a
 * development run creates it again, through the Flyway callback in {@code db/demo}, which only a checkout's
 * configuration and the compose stack name as a callback location.
 *
 * <p>Raw Flyway on databases of their own, as {@code AuthSessionMigrationIT} does: the shared database is
 * already at the latest version, and is migrated without the callback. "Shipped" here means the jar's own
 * settings, the default location and no callback location; "with the demo" adds {@code classpath:db/demo}
 * as the callback location, as {@code backend/config/application.yml} and {@code docker-compose.yml} do.
 */
class DemoAccountMigrationIT extends PostgresIT {

    /** The id V2 gave the account, which the callback reuses. */
    private static final UUID DEMO_ACCOUNT = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private static final String DEMO_EMAIL = "demo@healthupgrades.com";

    /** The password the README publishes for the account. */
    private static final String DEMO_PASSWORD = "demo1234";

    /** The callback location that holds the demo account's script. */
    private static final String DEMO_LOCATION = "classpath:db/demo";

    /** The last version before the account is deleted, at which V2's account and its rows still exist. */
    private static final String BEFORE_THE_DELETE = "20";

    /** The version that deletes V2's account. */
    private static final String THE_DELETE = "21";

    /** V2's six areas, each with the emoji V5 gave it in place of an icon-font name. */
    private static final Map<UUID, String> DEMO_AREA_ICONS = Map.of(
            UUID.fromString("b0000000-0000-0000-0000-000000000001"), "💧",
            UUID.fromString("b0000000-0000-0000-0000-000000000002"), "🏃",
            UUID.fromString("b0000000-0000-0000-0000-000000000003"), "🌙",
            UUID.fromString("b0000000-0000-0000-0000-000000000004"), "🥗",
            UUID.fromString("b0000000-0000-0000-0000-000000000005"), "🧘",
            UUID.fromString("b0000000-0000-0000-0000-000000000006"), "🌿");

    /** The demo's Hydration area, which a test renames. */
    private static final UUID HYDRATION_AREA = UUID.fromString("b0000000-0000-0000-0000-000000000001");

    /** The demo's active water upgrade, which the rows a test adds belong to. */
    private static final UUID WATER_UPGRADE = UUID.fromString("c0000000-0000-0000-0000-000000000001");

    /** The demo's idea upgrade, which a test deletes. */
    private static final UUID DISH_SOAP_UPGRADE = UUID.fromString("c0000000-0000-0000-0000-000000000005");

    private static final int DEMO_UPGRADES = 6;

    private static final int DEMO_TRACKING_CONFIGS = 4;

    /** A versioned migration's script name, the only kind flyway_schema_history may hold here. */
    private static final Pattern VERSIONED_SCRIPT = Pattern.compile("V\\d+__.*\\.sql");

    private static final String ANOTHER_EMAIL = "someone-else@example.com";

    /** Another account's password, only ever hashed, so a change to its hash can be seen. */
    private static final String ANOTHER_PASSWORD = "another-owners-password";

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * Every table a row of the demo account's can sit in, and how many rows in it are the account's. A
     * tracking configuration, a reminder and a spent token name no owner, so each is counted whole: in these
     * databases only the demo account owns any.
     */
    private static final String ROWS_OF_THE_DEMO_ACCOUNT = """
            WITH demo (id) AS (VALUES (CAST(? AS uuid)))
            SELECT 'users' AS table_name, count(*) AS row_count FROM users JOIN demo ON users.id = demo.id
            UNION ALL SELECT 'health_areas', count(*) FROM health_areas a JOIN demo ON a.user_id = demo.id
            UNION ALL SELECT 'health_upgrades', count(*) FROM health_upgrades u JOIN demo ON u.user_id = demo.id
            UNION ALL SELECT 'tracking_configs', count(*) FROM tracking_configs
            UNION ALL SELECT 'progress_entries', count(*) FROM progress_entries p JOIN demo ON p.user_id = demo.id
            UNION ALL SELECT 'reflections', count(*) FROM reflections r JOIN demo ON r.user_id = demo.id
            UNION ALL SELECT 'reminders', count(*) FROM reminders
            UNION ALL SELECT 'notifications', count(*) FROM notifications n JOIN demo ON n.user_id = demo.id
            UNION ALL SELECT 'auth_sessions', count(*) FROM auth_sessions s JOIN demo ON s.user_id = demo.id
            UNION ALL SELECT 'auth_session_spent_tokens', count(*) FROM auth_session_spent_tokens
            """;

    /**
     * What every new deployment's database looks like from now on: V2 still seeds the account, V21 takes it
     * away again, and nothing in the jar's configuration brings it back.
     */
    @Test
    void GivenAnEmptyDatabase_WhenTheShippedMigrationsRun_ThenThereIsNoDemoAccountAndNoneOfItsAreas()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_shipped_leaves_no_account");

        migrateShipped(database);

        try (Connection connection = database.connect()) {
            assertThat(count(
                            connection,
                            "SELECT count(*) FROM users WHERE id = ? OR email = ?",
                            DEMO_ACCOUNT,
                            DEMO_EMAIL))
                    .as("accounts holding the demo account's id or address")
                    .isZero();
            assertThat(count(
                            connection,
                            "SELECT count(*) FROM health_areas WHERE id = ANY (?)",
                            connection.createArrayOf(
                                    "uuid", DEMO_AREA_ICONS.keySet().toArray())))
                    .as("the demo account's areas")
                    .isZero();
        }
    }

    /**
     * A database that has been running for a while: the account has been used, so it owns a row in every
     * table that can hold one, and somebody else has an account too. The delete must reach every row of the
     * first, through the cascades alone, and spare the second.
     */
    @Test
    void GivenRowsTheDemoAccountOwnsAndAnotherAccount_WhenV21Runs_ThenOnlyTheDemoAccountsRowsAreGone()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_v21_deletes_what_it_owned");
        migrateShipped(database, BEFORE_THE_DELETE);
        UUID anotherAccount = UUID.randomUUID();
        UUID anotherAccountsArea = UUID.randomUUID();
        try (Connection connection = database.connect()) {
            insertRowsTheDemoAccountOwns(connection);
            insertAccount(connection, anotherAccount, ANOTHER_EMAIL, ENCODER.encode(ANOTHER_PASSWORD));
            execute(
                    connection,
                    "INSERT INTO health_areas (id, user_id, name) VALUES (?, ?, 'Strength')",
                    anotherAccountsArea,
                    anotherAccount);
            assertThat(rowsOfTheDemoAccount(connection))
                    .as("before V21, the demo account owns a row in every table that can hold one")
                    .isNotEmpty()
                    .allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());
        }

        migrateShipped(database, THE_DELETE);

        try (Connection connection = database.connect()) {
            assertThat(rowsOfTheDemoAccount(connection))
                    .as("after V21, rows the demo account owned")
                    .allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
            assertThat(count(connection, "SELECT count(*) FROM users WHERE id = ?", anotherAccount))
                    .as("another account, which V21 must not touch")
                    .isOne();
            assertThat(count(
                            connection,
                            "SELECT count(*) FROM health_areas WHERE id = ? AND user_id = ?",
                            anotherAccountsArea,
                            anotherAccount))
                    .as("that account's area")
                    .isOne();
        }
    }

    /**
     * The README's sign-in, on a fresh clone: an ordinary, enabled account whose hash the published password
     * matches.
     */
    @Test
    void GivenAnEmptyDatabase_WhenMigratedWithTheDemo_ThenTheDemoAccountSignsInWithThePublishedPassword()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_callback_creates_the_account");

        migrateWithTheDemo(database);

        try (Connection connection = database.connect();
                PreparedStatement select = connection.prepareStatement(
                        "SELECT email, role, enabled, password_hash FROM users WHERE id = ?")) {
            select.setObject(1, DEMO_ACCOUNT);
            try (ResultSet account = select.executeQuery()) {
                assertThat(account.next()).as("the demo account exists").isTrue();
                assertThat(account.getString("email")).isEqualTo(DEMO_EMAIL);
                assertThat(account.getString("role")).isEqualTo("USER");
                assertThat(account.getBoolean("enabled")).isTrue();
                assertThat(ENCODER.matches(DEMO_PASSWORD, account.getString("password_hash")))
                        .as("the published password matches the stored hash")
                        .isTrue();
            }
        }
    }

    /**
     * V2's content with V5's icons, so the demo looks as it always did. And every ACTIVE upgrade has the start
     * date {@code HealthUpgrade.activate} always sets, which V2's rows lacked: a row the domain could not have
     * written would take the demo down a path no real account takes.
     */
    @Test
    void
            GivenAnEmptyDatabase_WhenMigratedWithTheDemo_ThenTheAccountHasItsAreasUpgradesAndTrackingAsTheDomainWouldWriteThem()
                    throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_callback_creates_its_content");

        migrateWithTheDemo(database);

        try (Connection connection = database.connect()) {
            assertThat(areaIconsOf(connection, DEMO_ACCOUNT)).isEqualTo(DEMO_AREA_ICONS);
            assertThat(count(connection, "SELECT count(*) FROM health_upgrades WHERE user_id = ?", DEMO_ACCOUNT))
                    .isEqualTo(DEMO_UPGRADES);
            assertThat(count(connection, """
                            SELECT count(*) FROM tracking_configs c
                            JOIN health_upgrades u ON u.id = c.upgrade_id
                            WHERE u.user_id = ?
                            """, DEMO_ACCOUNT)).isEqualTo(DEMO_TRACKING_CONFIGS);
            assertThat(count(connection, """
                            SELECT count(*) FROM health_upgrades
                            WHERE user_id = ? AND status = 'ACTIVE' AND actual_start_date IS NULL
                            """, DEMO_ACCOUNT))
                    .as("ACTIVE upgrades with no start date")
                    .isZero();
        }
    }

    /**
     * A developer's database from before V21: V21 deletes the old account with its history, and the callback,
     * running after it, creates the account again, so the sign-in keeps working.
     */
    @Test
    void GivenADatabaseWithV2sDemoAccountAndItsProgress_WhenMigratedWithTheDemo_ThenTheAccountIsCreatedAfreshWithoutIt()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_callback_replaces_v2s_account");
        migrateShipped(database, BEFORE_THE_DELETE);
        UUID progress = UUID.randomUUID();
        try (Connection connection = database.connect()) {
            insertProgress(connection, progress);
        }

        migrateWithTheDemo(database);

        try (Connection connection = database.connect()) {
            assertThat(ENCODER.matches(DEMO_PASSWORD, passwordHashOf(connection, DEMO_ACCOUNT)))
                    .as("the recreated account signs in with the published password")
                    .isTrue();
            assertThat(count(connection, "SELECT count(*) FROM progress_entries WHERE id = ?", progress))
                    .as("the old account's progress, which V21 deleted with it")
                    .isZero();
        }
    }

    /**
     * The callback runs on every start. Restoring what the owner deleted, undoing a rename, or re-enabling an
     * account an administrator disabled would let every restart overrule them.
     */
    @Test
    void GivenADemoAccountItsOwnerChanged_WhenMigratedWithTheDemoAgain_ThenEveryChangeIsKeptAndNothingIsDuplicated()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_callback_keeps_owner_changes");
        migrateWithTheDemo(database);
        try (Connection connection = database.connect()) {
            execute(connection, "DELETE FROM health_upgrades WHERE id = ?", DISH_SOAP_UPGRADE);
            execute(connection, "UPDATE health_areas SET name = 'Water' WHERE id = ?", HYDRATION_AREA);
            execute(connection, "UPDATE users SET enabled = FALSE WHERE id = ?", DEMO_ACCOUNT);
        }

        migrateWithTheDemo(database);

        try (Connection connection = database.connect()) {
            assertThat(count(connection, "SELECT count(*) FROM health_upgrades WHERE id = ?", DISH_SOAP_UPGRADE))
                    .as("the upgrade its owner deleted")
                    .isZero();
            assertThat(count(
                            connection,
                            "SELECT count(*) FROM health_areas WHERE id = ? AND name = 'Water'",
                            HYDRATION_AREA))
                    .as("the area under the name its owner gave it")
                    .isOne();
            assertThat(count(connection, "SELECT count(*) FROM users WHERE id = ? AND NOT enabled", DEMO_ACCOUNT))
                    .as("the account, still disabled")
                    .isOne();
            assertThat(count(connection, "SELECT count(*) FROM users WHERE email = ?", DEMO_EMAIL))
                    .isOne();
            assertThat(count(connection, "SELECT count(*) FROM health_areas WHERE user_id = ?", DEMO_ACCOUNT))
                    .isEqualTo(DEMO_AREA_ICONS.size());
            assertThat(count(connection, "SELECT count(*) FROM health_upgrades WHERE user_id = ?", DEMO_ACCOUNT))
                    .isEqualTo(DEMO_UPGRADES - 1);
        }
    }

    /**
     * Once V21 has freed the address, anyone may register it. The callback must neither fail the start on the
     * address's uniqueness nor write anything that account would then appear to own.
     */
    @Test
    void GivenAnotherAccountHoldingTheDemoAddress_WhenMigratedWithTheDemo_ThenThatAccountIsUntouchedAndNoDemoIsCreated()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_callback_spares_another_owner");
        migrateShipped(database);
        UUID anotherAccount = UUID.randomUUID();
        String anotherAccountsHash = ENCODER.encode(ANOTHER_PASSWORD);
        try (Connection connection = database.connect()) {
            insertAccount(connection, anotherAccount, DEMO_EMAIL, anotherAccountsHash);
        }

        assertThatCode(() -> migrateWithTheDemo(database))
                .as("the callback, meeting the address already taken")
                .doesNotThrowAnyException();

        try (Connection connection = database.connect()) {
            assertThat(passwordHashOf(connection, anotherAccount))
                    .as("the hash of the account that holds the address")
                    .isEqualTo(anotherAccountsHash);
            assertThat(count(connection, "SELECT count(*) FROM users WHERE id = ?", DEMO_ACCOUNT))
                    .as("accounts under the demo account's id")
                    .isZero();
            assertThat(areaIconsOf(connection, anotherAccount))
                    .as("areas the callback gave the account holding the address")
                    .isEmpty();
        }
    }

    /**
     * A callback is never recorded, which is why the seed is one and not a repeatable migration: a database a
     * development run seeded still validates against a configuration that does not name the callback.
     */
    @Test
    void
            GivenADatabaseTheDemoCallbackRanOn_WhenTheShippedMigrationsRun_ThenItValidatesAndItsHistoryHoldsOnlyMigrations()
                    throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_callback_leaves_no_history");
        migrateWithTheDemo(database);

        migrateShipped(database);
        ValidateResult validation = shipped(database).load().validateWithResult();

        assertThat(validation.validationSuccessful)
                .as("validation, which failed with: %s", validation.getAllErrorMessages())
                .isTrue();
        try (Connection connection = database.connect()) {
            assertThat(historyScripts(connection))
                    .isNotEmpty()
                    .allMatch(script -> VERSIONED_SCRIPT.matcher(script).matches());
        }
    }

    private static FluentConfiguration shipped(IsolatedDatabase database) {
        return Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password());
    }

    private static void migrateShipped(IsolatedDatabase database) {
        shipped(database).load().migrate();
    }

    private static void migrateShipped(IsolatedDatabase database, String version) {
        shipped(database).target(version).load().migrate();
    }

    private static void migrateWithTheDemo(IsolatedDatabase database) {
        shipped(database).callbackLocations(DEMO_LOCATION).load().migrate();
    }

    /**
     * A row of the demo account's in every table that can hold one, each under {@link #WATER_UPGRADE} where it
     * belongs to an upgrade, as V20 stores them.
     */
    private static void insertRowsTheDemoAccountOwns(Connection connection) throws SQLException {
        UUID session = UUID.randomUUID();
        insertProgress(connection, UUID.randomUUID());
        execute(
                connection,
                "INSERT INTO reflections (id, upgrade_id, user_id, date) VALUES (?, ?, ?, CURRENT_DATE)",
                UUID.randomUUID(),
                WATER_UPGRADE,
                DEMO_ACCOUNT);
        execute(
                connection,
                "INSERT INTO reminders (id, upgrade_id, reminder_time, enabled) VALUES (?, ?, '08:00', TRUE)",
                UUID.randomUUID(),
                WATER_UPGRADE);
        execute(connection, """
                INSERT INTO notifications (id, user_id, type, category, title)
                VALUES (?, ?, 'REMINDER', 'REMINDER', 'Drink water')
                """, UUID.randomUUID(), DEMO_ACCOUNT);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO auth_sessions (id, user_id, refresh_token_hash, created_at, last_used_at,
                    idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, now(), now(), now() + interval '7 days', now() + interval '30 days')
                """)) {
            insert.setObject(1, session);
            insert.setObject(2, DEMO_ACCOUNT);
            insert.setBytes(3, new byte[] {1, 2, 3});
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO auth_session_spent_tokens (session_id, token_hash, spent_at) VALUES (?, ?, now())")) {
            insert.setObject(1, session);
            insert.setBytes(2, new byte[] {4, 5, 6});
            insert.executeUpdate();
        }
    }

    /** One day's progress on the demo's water upgrade. */
    private static void insertProgress(Connection connection, UUID id) throws SQLException {
        execute(
                connection,
                "INSERT INTO progress_entries (id, upgrade_id, user_id, date) VALUES (?, ?, ?, CURRENT_DATE)",
                id,
                WATER_UPGRADE,
                DEMO_ACCOUNT);
    }

    private static void insertAccount(Connection connection, UUID id, String email, String passwordHash)
            throws SQLException {
        execute(
                connection,
                "INSERT INTO users (id, name, email, password_hash) VALUES (?, 'Someone Else', ?, ?)",
                id,
                email,
                passwordHash);
    }

    /** {@link #ROWS_OF_THE_DEMO_ACCOUNT}, as each table's name to its count. */
    private static Map<String, Long> rowsOfTheDemoAccount(Connection connection) throws SQLException {
        Map<String, Long> rowsByTable = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(ROWS_OF_THE_DEMO_ACCOUNT)) {
            select.setObject(1, DEMO_ACCOUNT);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    rowsByTable.put(rows.getString("table_name"), rows.getLong("row_count"));
                }
            }
        }
        return rowsByTable;
    }

    private static Map<UUID, String> areaIconsOf(Connection connection, UUID owner) throws SQLException {
        Map<UUID, String> iconsByArea = new HashMap<>();
        try (PreparedStatement select =
                connection.prepareStatement("SELECT id, icon FROM health_areas WHERE user_id = ?")) {
            select.setObject(1, owner);
            try (ResultSet areas = select.executeQuery()) {
                while (areas.next()) {
                    iconsByArea.put(areas.getObject("id", UUID.class), areas.getString("icon"));
                }
            }
        }
        return iconsByArea;
    }

    private static String passwordHashOf(Connection connection, UUID account) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT password_hash FROM users WHERE id = ?")) {
            select.setObject(1, account);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("account %s exists", account).isTrue();
                return row.getString("password_hash");
            }
        }
    }

    private static List<String> historyScripts(Connection connection) throws SQLException {
        List<String> scripts = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT script FROM flyway_schema_history");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                scripts.add(rows.getString("script"));
            }
        }
        return scripts;
    }

    private static long count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            bind(select, parameters);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("a count returns a row").isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
