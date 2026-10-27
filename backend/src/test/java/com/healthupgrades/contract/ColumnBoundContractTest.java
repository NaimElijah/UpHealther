package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.auth.adapter.in.web.AuthController;
import com.healthupgrades.healtharea.adapter.in.web.HealthAreaRequest;
import com.healthupgrades.tracking.adapter.in.web.ProgressRequest;
import com.healthupgrades.tracking.adapter.in.web.TrackingConfigRequest;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins every {@code @Size(max = …)} bound on a request DTO against the column it mirrors.
 *
 * <p>BR-16 says a field stored in a bounded column is refused at the boundary rather than at the
 * flush, and the numbers that make that true are copied out of the migrations by hand. Nothing else
 * connects the two: the entities declare no {@code @Column(length)}, and Hibernate's {@code validate}
 * checks that a column exists and has a compatible type, not that it has a particular width. So a
 * migration that narrows a column leaves the DTO bound behind, and the exact 500 BR-16 exists to
 * prevent comes back — while {@code requirements.md} still claims it cannot.
 *
 * <p>This is the same class of gap as {@link FrontendWireTypesContractTest}: two sides of a contract with
 * no compiler between them. It is filled the same way, by reading the source of truth directly and
 * failing the build on a divergence.
 *
 * <p>The bound may be <em>equal</em> to the column and nothing else. Wider re-opens the defect;
 * narrower would silently refuse input the database would have accepted, which is a different defect
 * and not one to introduce by accident — if a field should be shorter than its column, that is a
 * product rule and belongs in the requirements with a test of its own.
 *
 * <p>The migration parser is itself tested below on fixture SQL, because a parser that silently reads
 * less than the migrations say makes every case above pass for the wrong reason.
 */
class ColumnBoundContractTest {

    /** Flyway's migration directory, relative to the repository root. */
    private static final Path MIGRATIONS = Paths.get("src", "main", "resources", "db", "migration");

    /** A bounded character type, capturing its width; {@code TEXT} has none, and BR-16 leaves it alone. */
    private static final String BOUNDED_TYPE = "(?:VARCHAR|CHARACTER\\s+VARYING)\\s*\\((\\d+)\\)";

    /** A Flyway versioned migration's file name, capturing its version: {@code V10__x.sql} is 10. */
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.*\\.sql$");

    /** A line comment or a block comment, removed before the statements are split. */
    private static final Pattern COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /** The {@code CREATE TABLE users (} that opens a table body. */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_]+)", Pattern.CASE_INSENSITIVE);

    /** A bounded column on a line of its own in a table body, e.g. {@code title VARCHAR(255)}. */
    private static final Pattern COLUMN =
            Pattern.compile("^\\s*([a-z_]+)\\s+" + BOUNDED_TYPE, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** The {@code ALTER TABLE users} that opens a statement changing a table that already exists. */
    private static final Pattern ALTER_TABLE =
            Pattern.compile("ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?([a-z_]+)", Pattern.CASE_INSENSITIVE);

    /**
     * A {@code CREATE TABLE} or {@code ALTER TABLE} anywhere in a statement, to catch one that does not
     * open it, as one inside a {@code DO} block does not.
     */
    private static final Pattern TABLE_DDL =
            Pattern.compile("\\b(?:CREATE|ALTER)\\s+TABLE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * One clause of an {@code ALTER TABLE} that changes a column's width, matched in the order written:
     * {@code ADD [COLUMN] c VARCHAR(n)}, {@code ALTER [COLUMN] c [SET DATA] TYPE t} and
     * {@code DROP [COLUMN] c}. A type change to anything but a bounded type leaves the column unbounded.
     */
    private static final Pattern ALTER_CLAUSE = Pattern.compile(
            "\\bADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(?<added>[a-z_]+)\\s+"
                    + "(?:VARCHAR|CHARACTER\\s+VARYING)\\s*\\((?<addedWidth>\\d+)\\)"
                    + "|\\bALTER\\s+(?:COLUMN\\s+)?(?<altered>[a-z_]+)\\s+(?:SET\\s+DATA\\s+)?TYPE\\s+"
                    + "(?:(?:VARCHAR|CHARACTER\\s+VARYING)\\s*\\((?<alteredWidth>\\d+)\\))?"
                    + "|\\bDROP\\s+(?:COLUMN\\s+)?(?:IF\\s+EXISTS\\s+)?(?<dropped>[a-z_]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Every bound this codebase declares, against the column it claims to mirror.
     *
     * <p>Adding a {@code @Size(max = …)} to a request DTO without adding a row here is the one hole
     * left, and {@link #GivenEveryBoundedColumn_WhenTheyAreListed_ThenEachOneIsClaimedByADto} is what
     * closes it from the other direction.
     */
    private static Stream<Arguments> declaredBounds() {
        return Stream.of(
                Arguments.of("health_upgrades", "title", UpgradeRequest.TITLE_MAX),
                Arguments.of("health_areas", "name", HealthAreaRequest.NAME_MAX),
                Arguments.of("health_areas", "icon", HealthAreaRequest.ICON_MAX),
                Arguments.of("health_areas", "color", HealthAreaRequest.COLOR_MAX),
                Arguments.of("progress_entries", "unit", ProgressRequest.UNIT_MAX),
                Arguments.of("tracking_configs", "target_unit", TrackingConfigRequest.TARGET_UNIT_MAX),
                Arguments.of("users", "name", AuthController.NAME_MAX),
                Arguments.of("users", "email", AuthController.EMAIL_MAX));
    }

    /**
     * Columns a request body never writes to, and why.
     *
     * <p>Listed rather than filtered by a rule, so that a new bounded column has to be considered by
     * somebody: the second test below fails until it appears in one list or the other.
     */
    private static final Map<String, String> UNBOUND_BY_DESIGN = Map.ofEntries(
            Map.entry("users.password_hash", "a BCrypt hash the server writes; never taken from a request"),
            Map.entry("users.role", "an enum, bound by deserialization on the one endpoint that writes it"),
            Map.entry("health_upgrades.type", "an enum, bound by deserialization before validation runs"),
            Map.entry("health_upgrades.status", "an enum, and moved only by the transition endpoints"),
            Map.entry("health_upgrades.difficulty", "an enum, bound by deserialization"),
            Map.entry("tracking_configs.tracking_type", "an enum, bound by deserialization"),
            Map.entry("tracking_configs.frequency", "an enum, bound by deserialization"),
            Map.entry(
                    "reminders.days_of_week",
                    "written by ReminderDays, which rejects any token outside the seven valid ones, so the "
                            + "joined string cannot exceed 27 characters"),
            Map.entry("notifications.type", "an enum, bound by deserialization"),
            Map.entry("notifications.category", "an enum, bound by deserialization"),
            Map.entry("notifications.title", "written by the server from a template, never from a request"),
            Map.entry("notifications.message", "written by the server from a template, never from a request"));

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("declaredBounds")
    void GivenABoundDeclaredOnADto_WhenItIsComparedToItsColumn_ThenTheyAgree(
            String table, String column, int declared) {
        Integer actual = boundedColumns().get(table + "." + column);

        assertThat(actual)
                .as(
                        "%s.%s is declared as a bound on a request DTO but no migration creates it as a "
                                + "bounded column",
                        table, column)
                .isNotNull();
        assertThat(declared)
                .as(
                        "the DTO bounds %s.%s at %d but the column holds %d — a wider bound re-opens the "
                                + "500 BR-16 prevents, a narrower one refuses input the database would accept",
                        table, column, declared, actual)
                .isEqualTo(actual);
    }

    @Test
    void GivenEveryBoundedColumn_WhenTheyAreListed_ThenEachOneIsClaimedByADtoOrExcusedByName() {
        List<String> claimed =
                declaredBounds().map(a -> a.get()[0] + "." + a.get()[1]).toList();

        assertThat(boundedColumns().keySet())
                .as("a bounded column is either mirrored by a DTO bound (BR-16) or listed as one no "
                        + "request body writes to — a new one must be considered, not defaulted")
                .allSatisfy(
                        qualified -> assertThat(claimed.contains(qualified) || UNBOUND_BY_DESIGN.containsKey(qualified))
                                .as("%s is neither bounded by a DTO nor excused in UNBOUND_BY_DESIGN", qualified)
                                .isTrue());
    }

    // ---- the migration parser, on fixture SQL ----

    private static final String CREATE_UPGRADES = """
            CREATE TABLE health_upgrades (
                id UUID PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                description TEXT
            );
            """;

    @Test
    void GivenATableCreatedWithBoundedAndUnboundedColumns_WhenTheMigrationsAreParsed_ThenOnlyTheBoundedOnesAreRead() {
        assertThat(boundedColumns(Map.of("V1__init.sql", CREATE_UPGRADES)))
                .containsExactlyEntriesOf(Map.of("health_upgrades.title", 255));
    }

    @Test
    void GivenALaterMigrationNarrowsAColumn_WhenTheMigrationsAreParsed_ThenTheNarrowerWidthIsRead() {
        Map<String, String> migrations = Map.of(
                "V1__init.sql",
                CREATE_UPGRADES,
                "V2__narrow_title.sql",
                "ALTER TABLE health_upgrades ALTER COLUMN title TYPE VARCHAR(200);");

        assertThat(boundedColumns(migrations)).containsEntry("health_upgrades.title", 200);
    }

    @Test
    void GivenAWrappedSetDataTypeClause_WhenTheMigrationsAreParsed_ThenTheNewWidthIsRead() {
        Map<String, String> migrations = Map.of("V1__init.sql", CREATE_UPGRADES, "V2__narrow_title.sql", """
                ALTER TABLE health_upgrades
                    ALTER COLUMN title SET DATA TYPE varchar(120);
                """);

        assertThat(boundedColumns(migrations)).containsEntry("health_upgrades.title", 120);
    }

    @Test
    void GivenMigrationsNumberedPastNine_WhenTheMigrationsAreParsed_ThenTheyApplyInVersionOrderNotNameOrder() {
        // By name V10 sorts before V2, so the CREATE would overwrite the narrowing that follows it.
        Map<String, String> migrations = Map.of(
                "V2__init.sql",
                CREATE_UPGRADES,
                "V10__narrow_title.sql",
                "ALTER TABLE health_upgrades ALTER COLUMN title TYPE VARCHAR(100);");

        assertThat(boundedColumns(migrations)).containsEntry("health_upgrades.title", 100);
    }

    @Test
    void GivenACreateAndANarrowingInOneFile_WhenTheMigrationsAreParsed_ThenTheLaterStatementWins() {
        Map<String, String> migrations = Map.of(
                "V1__init.sql", CREATE_UPGRADES + "ALTER TABLE health_upgrades ALTER COLUMN title TYPE VARCHAR(80);");

        assertThat(boundedColumns(migrations)).containsEntry("health_upgrades.title", 80);
    }

    @Test
    void GivenAColumnAddedByALaterMigration_WhenTheMigrationsAreParsed_ThenItIsRead() {
        Map<String, String> migrations = Map.of(
                "V1__init.sql",
                CREATE_UPGRADES,
                "V2__add_slug.sql",
                "ALTER TABLE health_upgrades ADD COLUMN slug VARCHAR(64);");

        assertThat(boundedColumns(migrations)).containsEntry("health_upgrades.slug", 64);
    }

    @Test
    void GivenAColumnChangedToTextOrDropped_WhenTheMigrationsAreParsed_ThenItIsNoLongerBounded() {
        Map<String, String> migrations = Map.of("V1__init.sql", """
                CREATE TABLE health_upgrades (
                    title VARCHAR(255) NOT NULL,
                    summary VARCHAR(500)
                );
                """, "V2__unbound.sql", """
                ALTER TABLE health_upgrades ALTER COLUMN title TYPE TEXT, DROP COLUMN summary;
                """);

        assertThat(boundedColumns(migrations)).isEmpty();
    }

    @Test
    void GivenAStatementOnlyInAComment_WhenTheMigrationsAreParsed_ThenItIsIgnored() {
        Map<String, String> migrations = Map.of(
                "V1__init.sql",
                CREATE_UPGRADES,
                "V2__note.sql",
                "-- ALTER TABLE health_upgrades ALTER COLUMN title TYPE VARCHAR(10);\nSELECT 1;");

        assertThat(boundedColumns(migrations)).containsEntry("health_upgrades.title", 255);
    }

    @Test
    void GivenAFileThatIsNotAVersionedMigration_WhenTheMigrationsAreParsed_ThenItIsRefusedRatherThanGuessed() {
        Map<String, String> migrations = Map.of("V1__init.sql", CREATE_UPGRADES, "init.sql", CREATE_UPGRADES);

        assertThatThrownBy(() -> boundedColumns(migrations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("init.sql");
    }

    @Test
    void GivenANarrowingInsideADoBlock_WhenTheMigrationsAreParsed_ThenItIsRefusedRatherThanSkipped() {
        // Skipped, the narrowing would leave the title at a width it no longer has.
        Map<String, String> migrations = Map.of("V1__init.sql", CREATE_UPGRADES, "V2__narrow_title.sql", """
                DO $$
                BEGIN
                    ALTER TABLE health_upgrades ALTER COLUMN title TYPE VARCHAR(100);
                END $$;
                """);

        assertThatThrownBy(() -> boundedColumns(migrations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("V2__narrow_title.sql");
    }

    /**
     * Every {@code table.column} the real migrations leave as a {@code VARCHAR(n)}, mapped to that
     * {@code n}.
     */
    private static Map<String, Integer> boundedColumns() {
        Map<String, String> sqlByFileName = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                sqlByFileName.put(file.getFileName().toString(), Files.readString(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the migrations at " + MIGRATIONS.toAbsolutePath(), e);
        }
        Map<String, Integer> columns = boundedColumns(sqlByFileName);
        assertThat(columns)
                .as(
                        "no bounded columns were parsed out of %s — the migrations moved, or their shape "
                                + "changed and this test is now reading nothing",
                        MIGRATIONS.toAbsolutePath())
                .isNotEmpty();
        return columns;
    }

    /**
     * The width each bounded column has once every migration has run, as {@code table.column} to
     * {@code n}.
     *
     * <p>Applies the migrations in Flyway's order, by version number rather than by name ({@code V10}
     * runs after {@code V9}, though it sorts before {@code V2}), and each file's statements in the order
     * written, so a later {@code ALTER} that narrows, widens, unbounds or drops a column replaces what
     * the {@code CREATE} declared. A rename is not followed: the new name then reads as missing, and the
     * DTO case fails loudly rather than passing on the old one.
     *
     * @param sqlByFileName each migration's file name and its SQL, in any order
     * @return the bounded columns, in the order they were first declared
     * @throws IllegalArgumentException if a file name is not a versioned migration's, or a file has a
     *     {@code CREATE TABLE} or {@code ALTER TABLE} that does not start its statement, whose widths
     *     this parser cannot follow
     */
    private static Map<String, Integer> boundedColumns(Map<String, String> sqlByFileName) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        sqlByFileName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ColumnBoundContractTest::compareVersions))
                .forEach(migration -> apply(migration.getKey(), migration.getValue(), columns));
        return columns;
    }

    /** Applies one migration's statements, in order, to the widths read so far. */
    private static void apply(String fileName, String sql, Map<String, Integer> columns) {
        for (String statement : COMMENT.matcher(sql).replaceAll("").split(";")) {
            String trimmed = statement.strip();
            Matcher create = CREATE_TABLE.matcher(trimmed);
            if (create.lookingAt()) {
                String table = create.group(1).toLowerCase();
                Matcher column = COLUMN.matcher(trimmed);
                while (column.find()) {
                    columns.put(qualified(table, column.group(1)), Integer.parseInt(column.group(2)));
                }
                continue;
            }
            Matcher alter = ALTER_TABLE.matcher(trimmed);
            if (alter.lookingAt()) {
                applyAlter(alter.group(1).toLowerCase(), trimmed.substring(alter.end()), columns);
                continue;
            }
            if (TABLE_DDL.matcher(trimmed).find()) {
                throw new IllegalArgumentException(fileName
                        + " has a CREATE TABLE or ALTER TABLE that does not start its statement, so the widths"
                        + " it sets cannot be read: " + trimmed);
            }
        }
    }

    private static void applyAlter(String table, String clauses, Map<String, Integer> columns) {
        Matcher clause = ALTER_CLAUSE.matcher(clauses);
        while (clause.find()) {
            if (clause.group("added") != null) {
                columns.put(qualified(table, clause.group("added")), Integer.parseInt(clause.group("addedWidth")));
            } else if (clause.group("altered") != null) {
                String altered = qualified(table, clause.group("altered"));
                if (clause.group("alteredWidth") != null) {
                    columns.put(altered, Integer.parseInt(clause.group("alteredWidth")));
                } else {
                    columns.remove(altered);
                }
            } else {
                columns.remove(qualified(table, clause.group("dropped")));
            }
        }
    }

    private static String qualified(String table, String column) {
        return table + "." + column.toLowerCase();
    }

    /** Orders two migration file names by Flyway version, numerically and part by part. */
    private static int compareVersions(String left, String right) {
        int[] l = version(left);
        int[] r = version(right);
        for (int i = 0; i < Math.min(l.length, r.length); i++) {
            if (l[i] != r[i]) {
                return Integer.compare(l[i], r[i]);
            }
        }
        return Integer.compare(l.length, r.length);
    }

    private static int[] version(String fileName) {
        Matcher versioned = VERSIONED.matcher(fileName);
        if (!versioned.matches()) {
            throw new IllegalArgumentException(fileName
                    + " is not a versioned migration (V<version>__<name>.sql), so its place in the order is unknown");
        }
        return Arrays.stream(versioned.group(1).split("[._]"))
                .mapToInt(Integer::parseInt)
                .toArray();
    }
}
