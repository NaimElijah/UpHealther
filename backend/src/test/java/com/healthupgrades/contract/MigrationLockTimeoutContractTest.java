package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Requires every migration from {@code V10} on to bound how long it waits for a lock, before it asks for
 * one (ADR-072).
 *
 * <p>A DDL statement queues for its lock behind any transaction that holds the table, and every query on
 * that table then queues behind the DDL. A migration that waits without a bound therefore stalls the
 * table's traffic for as long as the oldest transaction lives. {@code SET LOCAL lock_timeout} turns that
 * into a failed start instead. {@code LOCAL} because the setting must end with Flyway's transaction:
 * Flyway borrows its connection from the application's pool and hands it back, so a session-level
 * {@code SET} would go on bounding the application's own queries.
 *
 * <p>The setting must be the first statement, because a lock taken before it was taken without a bound.
 */
class MigrationLockTimeoutContractTest {

    /**
     * The first version the rule binds. V1 to V9 were applied without it, and Flyway checksums an applied
     * migration, so they cannot gain it now.
     */
    private static final int FIRST_BOUNDED_VERSION = 10;

    /**
     * {@code SET LOCAL lock_timeout} to a positive duration: {@code 0} would turn the bound off. A bare
     * number is milliseconds.
     */
    private static final Pattern BOUNDS_ITS_LOCK_WAIT = Pattern.compile(
            "SET\\s+LOCAL\\s+lock_timeout\\s*(?:=|TO)\\s*'0*[1-9]\\d*\\s*(?:ms|s|min)?'", Pattern.CASE_INSENSITIVE);

    static List<FlywayMigrations.Migration> boundedMigrations() {
        return FlywayMigrations.read().stream()
                .filter(migration -> migration.isAtLeast(FIRST_BOUNDED_VERSION))
                .toList();
    }

    @Test
    void GivenTheMigrationDirectory_WhenTheBoundedMigrationsAreListed_ThenThereIsAtLeastOne() {
        // Otherwise the parameterised case below runs for nothing, and passes.
        assertThat(boundedMigrations()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundedMigrations")
    void GivenAMigrationFromV10On_WhenItsStatementsAreRead_ThenTheFirstSetsALocalLockTimeout(
            FlywayMigrations.Migration migration) {
        assertThat(boundsItsLockWait(migration))
                .as(
                        "%s must open with SET LOCAL lock_timeout = '<n>s' (ADR-072): without it a lock it"
                                + " queues for stalls every query on the table behind it",
                        migration.fileName())
                .isTrue();
    }

    // ---- the rule, on fixture SQL ----

    @Test
    void GivenAMigrationThatOpensWithTheTimeoutUnderItsComments_WhenChecked_ThenItPasses() {
        assertThat(boundsItsLockWait(migration("V11__x.sql", """
                -- Why this migration exists.
                SET LOCAL lock_timeout = '5s';
                CREATE INDEX idx_x ON x (y);
                """))).isTrue();
    }

    @Test
    void GivenAMigrationWithoutTheTimeout_WhenChecked_ThenItFails() {
        assertThat(boundsItsLockWait(migration("V11__x.sql", "CREATE INDEX idx_x ON x (y);")))
                .isFalse();
    }

    @Test
    void GivenATimeoutSetAfterTheFirstStatement_WhenChecked_ThenItFails() {
        assertThat(boundsItsLockWait(migration("V11__x.sql", """
                ALTER TABLE x ADD COLUMN z INTEGER;
                SET LOCAL lock_timeout = '5s';
                """))).isFalse();
    }

    @Test
    void GivenASessionLevelTimeout_WhenChecked_ThenItFails() {
        // It would outlive the migration on the pooled connection Flyway hands back.
        assertThat(boundsItsLockWait(migration("V11__x.sql", "SET lock_timeout = '5s'; CREATE INDEX i ON x (y);")))
                .isFalse();
    }

    @Test
    void GivenATimeoutOfZero_WhenChecked_ThenItFails() {
        assertThat(boundsItsLockWait(migration("V11__x.sql", "SET LOCAL lock_timeout = '0'; CREATE INDEX i ON x (y);")))
                .isFalse();
    }

    @Test
    void GivenTheTimeoutOnlyInAComment_WhenChecked_ThenItFails() {
        assertThat(boundsItsLockWait(migration("V11__x.sql", """
                -- SET LOCAL lock_timeout = '5s';
                CREATE INDEX idx_x ON x (y);
                """))).isFalse();
    }

    @Test
    void GivenMigrationsEitherSideOfV10_WhenTheBoundedOnesAreChosen_ThenOnlyV10AndLaterAreKept() {
        List<FlywayMigrations.Migration> migrations = FlywayMigrations.inVersionOrder(
                Map.of("V9__before.sql", "SELECT 1;", "V10__first.sql", "SELECT 1;", "V10_1__patch.sql", "SELECT 1;"));

        assertThat(migrations.stream()
                        .filter(migration -> migration.isAtLeast(FIRST_BOUNDED_VERSION))
                        .map(FlywayMigrations.Migration::fileName))
                .containsExactly("V10__first.sql", "V10_1__patch.sql");
    }

    /** Whether the migration's first statement, comments aside, bounds its lock wait for its transaction. */
    private static boolean boundsItsLockWait(FlywayMigrations.Migration migration) {
        List<String> statements = migration.statements();
        return !statements.isEmpty()
                && BOUNDS_ITS_LOCK_WAIT.matcher(statements.getFirst()).matches();
    }

    private static FlywayMigrations.Migration migration(String fileName, String sql) {
        return FlywayMigrations.inVersionOrder(Map.of(fileName, sql)).getFirst();
    }
}
