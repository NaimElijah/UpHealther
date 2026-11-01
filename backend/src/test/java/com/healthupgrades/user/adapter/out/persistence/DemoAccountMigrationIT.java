package com.healthupgrades.user.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.support.RepositoryVersion;
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
 * development run creates it again, through the Flyway callback in {@code backend/config/db/demo}, which
 * only a checkout's configuration and the compose stack name as a callback location, and which neither the
 * jar nor the image carries.
 *
 * <p>Raw Flyway on databases of their own, as {@code AuthSessionMigrationIT} does: the shared database is
 * already at the latest version, and is migrated without the callback. "Shipped" here means the jar's own
 * settings, the default location and no callback location; "with the demo" adds that folder as a
 * {@code filesystem:} callback location, as {@code backend/config/application.yml} and
 * {@code docker-compose.yml} do. Named by its absolute path, so the test does not depend on the directory
 * it runs from.
 */
class DemoAccountMigrationIT extends PostgresIT {

    /** The id V2 gave the account, which the callback reuses. */
    private static final UUID DEMO_ACCOUNT = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private static final String DEMO_EMAIL = "demo@healthupgrades.com";

    /** The password the README publishes for the account. */
    private static final String DEMO_PASSWORD = "demo1234";

    /** The callback location that holds the demo account's script, as an absolute filesystem location. */
    private static final String DEMO_LOCATION = "filesystem:"
            + RepositoryVersion.repositoryRoot()
                    .resolve("backend/config/db/demo")
                    .toAbsolutePath();

    /**
     * The last version before V13's owner key on an upgrade's area, which refuses a new upgrade filed under
     * another owner's area. A row filed so before it is one V18's single-column key exists to detach.
     */
    private static final String BEFORE_THE_OWNER_KEYS = "12";

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

    /** An address the demo row is moved to, so that another account can hold the demo address beside it. */
    private static final String READDRESSED_DEMO_EMAIL = "readdressed-demo@example.com";

    /** Another account's password, only ever hashed, so a change to its hash can be seen. */
    private static final String ANOTHER_PASSWORD = "another-owners-password";

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * Every table a row of an account's can sit in, and how many rows in it are that account's. A tracking
     * configuration, a reminder and a spent token name no owner of their own, so each is counted through the
     * parent that does: its upgrade, or its session.
     */
    private static final String ROWS_OF_AN_ACCOUNT = """
            WITH account (id) AS (VALUES (CAST(? AS uuid)))
            SELECT 'users' AS table_name, count(*) AS row_count FROM users JOIN account ON users.id = account.id
            UNION ALL SELECT 'health_areas', count(*)
                FROM health_areas a JOIN account ON a.user_id = account.id
            UNION ALL SELECT 'health_upgrades', count(*)
                FROM health_upgrades u JOIN account ON u.user_id = account.id
            UNION ALL SELECT 'tracking_configs', count(*)
                FROM tracking_configs c JOIN health_upgrades u ON u.id = c.upgrade_id
                JOIN account ON u.user_id = account.id
            UNION ALL SELECT 'progress_entries', count(*)
                FROM progress_entries p JOIN account ON p.user_id = account.id
            UNION ALL SELECT 'reflections', count(*)
                FROM reflections r JOIN account ON r.user_id = account.id
            UNION ALL SELECT 'reminders', count(*)
                FROM reminders m JOIN health_upgrades u ON u.id = m.upgrade_id
                JOIN account ON u.user_id = account.id
            UNION ALL SELECT 'notifications', count(*)
                FROM notifications n JOIN account ON n.user_id = account.id
            UNION ALL SELECT 'auth_sessions', count(*)
                FROM auth_sessions s JOIN account ON s.user_id = account.id
            UNION ALL SELECT 'auth_session_spent_tokens', count(*)
                FROM auth_session_spent_tokens t JOIN auth_sessions s ON s.id = t.session_id
                JOIN account ON s.user_id = account.id
            """;

    /**
     * What every new deployment's database looks like from now on: V2 still seeds the account, V21 takes it
     * away again, and nothing in the jar's configuration brings it back. NFR-62 asks more than that: the
     * migrations create no account of any kind, so a later one that seeded another would fail here too.
     */
    @Test
    void GivenAnEmptyDatabase_WhenTheShippedMigrationsRun_ThenItHoldsNoAccountAtAllAndNoneOfTheDemosAreas()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_shipped_leaves_no_account");

        migrateShipped(database);

        try (Connection connection = database.connect()) {
            assertThat(count(connection, "SELECT count(*) FROM users"))
                    .as("accounts the migrations created, which only registration may")
                    .isZero();
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
     * A database that has been running for a while: both the demo account and another account own a row in
     * every table that can hold one. The delete must reach every row of the first, through the cascades
     * alone, and leave every row of the second.
     *
     * <p>Two of the second account's rows point at the first's, which is where a cascade could reach too
     * far: a notification about a demo upgrade, which loses only that link, and an upgrade filed under a demo
     * area before V13's owner key refused such a filing. V18's single-column key detaches that upgrade, and
     * V14's trigger moves its version by one, so a request holding the old version is refused, not let
     * through to write the deleted area back.
     */
    @Test
    void GivenRowsOfTheDemoAndOfAnotherAccountPointingAtIt_WhenV21Runs_ThenOnlyTheDemosRowsAndTheLinksToThemGo()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_v21_deletes_what_it_owned");
        UUID anotherAccount = UUID.randomUUID();
        UUID filedUnderADemoArea = UUID.randomUUID();
        migrateShipped(database, BEFORE_THE_OWNER_KEYS);
        try (Connection connection = database.connect()) {
            insertAccount(connection, anotherAccount, ANOTHER_EMAIL, ENCODER.encode(ANOTHER_PASSWORD));
            insertUpgrade(connection, filedUnderADemoArea, anotherAccount, HYDRATION_AREA);
        }
        migrateShipped(database, BEFORE_THE_DELETE);
        UUID anotherArea = UUID.randomUUID();
        UUID anotherUpgrade = UUID.randomUUID();
        UUID notificationAboutADemoUpgrade = UUID.randomUUID();
        Map<String, Long> anotherAccountsRows;
        long versionBeforeTheDelete;
        try (Connection connection = database.connect()) {
            insertRowsOwnedBy(connection, DEMO_ACCOUNT, WATER_UPGRADE, UUID.randomUUID());
            execute(
                    connection,
                    "INSERT INTO health_areas (id, user_id, name) VALUES (?, ?, 'Strength')",
                    anotherArea,
                    anotherAccount);
            insertUpgrade(connection, anotherUpgrade, anotherAccount, anotherArea);
            execute(
                    connection,
                    "INSERT INTO tracking_configs (id, upgrade_id, tracking_type) VALUES (?, ?, 'BOOLEAN')",
                    UUID.randomUUID(),
                    anotherUpgrade);
            insertRowsOwnedBy(connection, anotherAccount, anotherUpgrade, notificationAboutADemoUpgrade);
            assertThat(rowsOf(connection, DEMO_ACCOUNT))
                    .as("before V21, the demo account owns a row in every table that can hold one")
                    .isNotEmpty()
                    .allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());
            anotherAccountsRows = rowsOf(connection, anotherAccount);
            assertThat(anotherAccountsRows)
                    .as("before V21, the other account owns a row in every table that can hold one")
                    .allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());
            versionBeforeTheDelete = versionOf(connection, filedUnderADemoArea);
        }

        migrateShipped(database, THE_DELETE);

        try (Connection connection = database.connect()) {
            assertThat(rowsOf(connection, DEMO_ACCOUNT))
                    .as("after V21, rows the demo account owned")
                    .allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
            assertThat(rowsOf(connection, anotherAccount))
                    .as("after V21, the other account's rows, of which V21 must delete none")
                    .isEqualTo(anotherAccountsRows);
            assertThat(count(
                            connection,
                            "SELECT count(*) FROM notifications WHERE id = ? AND related_upgrade_id IS NULL",
                            notificationAboutADemoUpgrade))
                    .as("the other account's notification about a demo upgrade, kept without the link")
                    .isOne();
            assertThat(count(
                            connection,
                            "SELECT count(*) FROM health_upgrades WHERE id = ? AND area_id IS NULL",
                            filedUnderADemoArea))
                    .as("the other account's upgrade filed under a demo area, kept and unfiled")
                    .isOne();
            assertThat(versionOf(connection, filedUnderADemoArea))
                    .as("its version, which V14's trigger moves once when the area is detached")
                    .isEqualTo(versionBeforeTheDelete + 1);
        }
    }

    /**
     * V21 matches the fixed id alone, never the address. An account that holds the demo address under another
     * id was registered by somebody, and is theirs; a delete that also matched the address would take it with
     * everything it owns. Here the demo row has moved to another address first, as only a direct write could
     * move it, so that both rows exist when V21 runs.
     */
    @Test
    void GivenAnotherAccountHoldingTheDemoAddressBeforeV21_WhenV21Runs_ThenOnlyTheFixedIdIsDeleted()
            throws SQLException {
        IsolatedDatabase database = createIsolatedDatabase("demo_v21_matches_the_id_alone");
        migrateShipped(database, BEFORE_THE_DELETE);
        UUID addressHolder = UUID.randomUUID();
        try (Connection connection = database.connect()) {
            execute(connection, "UPDATE users SET email = ? WHERE id = ?", READDRESSED_DEMO_EMAIL, DEMO_ACCOUNT);
            insertAccount(connection, addressHolder, DEMO_EMAIL, ENCODER.encode(ANOTHER_PASSWORD));
        }

        migrateShipped(database, THE_DELETE);

        try (Connection connection = database.connect()) {
            assertThat(count(
                            connection,
                            "SELECT count(*) FROM users WHERE id = ? AND email = ?",
                            addressHolder,
                            DEMO_EMAIL))
                    .as("the account that holds the demo address under its own id")
                    .isOne();
            assertThat(count(connection, "SELECT count(*) FROM users WHERE id = ?", DEMO_ACCOUNT))
                    .as("the row under the demo account's fixed id")
                    .isZero();
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
            insertProgress(connection, progress, DEMO_ACCOUNT, WATER_UPGRADE);
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
     *
     * <p>Nothing in this repository can turn it red; only a Flyway release that began recording callbacks in
     * its schema history could. It pins the library's behaviour that the seed's design rests on, so an upgrade
     * that changed it fails here rather than at a deployment's first start.
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

    /**
     * Fails on a missing location, unlike Boot's default: Flyway would otherwise skip a wrong path with an
     * error in the log, and each test would fail on its assertions without saying why.
     */
    private static void migrateWithTheDemo(IsolatedDatabase database) {
        shipped(database)
                .callbackLocations(DEMO_LOCATION)
                .failOnMissingLocations(true)
                .load()
                .migrate();
    }

    /**
     * An account's row in each table that names an owner, an upgrade or a session, as V20 stores them: a
     * progress entry, a reflection and a reminder on the account's upgrade, a session with a spent token, and
     * a notification about the demo's water upgrade, whoever owns the notification.
     *
     * @param account      the owner
     * @param upgrade      one of the owner's upgrades, which the progress, reflection and reminder are on
     * @param notification the notification's id, for a caller that checks what became of it
     */
    private static void insertRowsOwnedBy(Connection connection, UUID account, UUID upgrade, UUID notification)
            throws SQLException {
        UUID session = UUID.randomUUID();
        insertProgress(connection, UUID.randomUUID(), account, upgrade);
        execute(
                connection,
                "INSERT INTO reflections (id, upgrade_id, user_id, date) VALUES (?, ?, ?, CURRENT_DATE)",
                UUID.randomUUID(),
                upgrade,
                account);
        execute(
                connection,
                "INSERT INTO reminders (id, upgrade_id, reminder_time, enabled) VALUES (?, ?, '08:00', TRUE)",
                UUID.randomUUID(),
                upgrade);
        execute(connection, """
                INSERT INTO notifications (id, user_id, type, category, title, related_upgrade_id)
                VALUES (?, ?, 'REMINDER', 'REMINDER', 'Drink water', ?)
                """, notification, account, WATER_UPGRADE);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO auth_sessions (id, user_id, refresh_token_hash, created_at, last_used_at,
                    idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, now(), now(), now() + interval '7 days', now() + interval '30 days')
                """)) {
            insert.setObject(1, session);
            insert.setObject(2, account);
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

    /** One day's progress, today, on an account's upgrade. */
    private static void insertProgress(Connection connection, UUID id, UUID account, UUID upgrade) throws SQLException {
        execute(
                connection,
                "INSERT INTO progress_entries (id, upgrade_id, user_id, date) VALUES (?, ?, ?, CURRENT_DATE)",
                id,
                upgrade,
                account);
    }

    /** An idea, filed under the given area, with every other column at its default. */
    private static void insertUpgrade(Connection connection, UUID id, UUID owner, UUID area) throws SQLException {
        execute(connection, """
                INSERT INTO health_upgrades (id, user_id, area_id, title, type, status)
                VALUES (?, ?, ?, 'Lift weights', 'HABIT', 'IDEA')
                """, id, owner, area);
    }

    private static long versionOf(Connection connection, UUID upgrade) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT version FROM health_upgrades WHERE id = ?")) {
            select.setObject(1, upgrade);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("upgrade %s exists", upgrade).isTrue();
                return row.getLong("version");
            }
        }
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

    /** {@link #ROWS_OF_AN_ACCOUNT} for the given account, as each table's name to its count. */
    private static Map<String, Long> rowsOf(Connection connection, UUID account) throws SQLException {
        Map<String, Long> rowsByTable = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(ROWS_OF_AN_ACCOUNT)) {
            select.setObject(1, account);
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
