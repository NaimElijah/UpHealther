package com.healthupgrades.contract;

import com.healthupgrades.auth.adapter.in.web.AuthController;
import com.healthupgrades.healtharea.adapter.in.web.HealthAreaRequest;
import com.healthupgrades.tracking.adapter.in.web.ProgressRequest;
import com.healthupgrades.tracking.adapter.in.web.TrackingConfigRequest;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>This is the same class of gap as {@link FrontendEnumContractTest}: two sides of a contract with
 * no compiler between them. It is filled the same way, by reading the source of truth directly and
 * failing the build on a divergence.
 *
 * <p>The bound may be <em>equal</em> to the column and nothing else. Wider re-opens the defect;
 * narrower would silently refuse input the database would have accepted, which is a different defect
 * and not one to introduce by accident — if a field should be shorter than its column, that is a
 * product rule and belongs in the requirements with a test of its own.
 */
class ColumnBoundContractTest {

    /** Flyway's migration directory, relative to the repository root. */
    private static final Path MIGRATIONS =
            Paths.get("src", "main", "resources", "db", "migration");

    /**
     * Matches a bounded character column in a {@code CREATE TABLE}, e.g. {@code title VARCHAR(255)}.
     * Deliberately not matched: {@code TEXT}, which has no bound and which BR-16 leaves alone.
     */
    private static final Pattern COLUMN =
            Pattern.compile("^\\s*([a-z_]+)\\s+VARCHAR\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

    /** Matches the {@code CREATE TABLE users (} that opens a table body. */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_]+)", Pattern.CASE_INSENSITIVE);

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
            Map.entry("health_upgrades.type", "an enum, bound by deserialization before validation runs"),
            Map.entry("health_upgrades.status", "an enum, and moved only by the transition endpoints"),
            Map.entry("health_upgrades.difficulty", "an enum, bound by deserialization"),
            Map.entry("tracking_configs.tracking_type", "an enum, bound by deserialization"),
            Map.entry("tracking_configs.frequency", "an enum, bound by deserialization"),
            Map.entry("reminders.days_of_week",
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
                .as("%s.%s is declared as a bound on a request DTO but no migration creates it as a "
                        + "bounded column", table, column)
                .isNotNull();
        assertThat(declared)
                .as("the DTO bounds %s.%s at %d but the column holds %d — a wider bound re-opens the "
                        + "500 BR-16 prevents, a narrower one refuses input the database would accept",
                        table, column, declared, actual)
                .isEqualTo(actual);
    }

    @Test
    void GivenEveryBoundedColumn_WhenTheyAreListed_ThenEachOneIsClaimedByADtoOrExcusedByName() {
        List<String> claimed = declaredBounds()
                .map(a -> a.get()[0] + "." + a.get()[1])
                .toList();

        assertThat(boundedColumns().keySet())
                .as("a bounded column is either mirrored by a DTO bound (BR-16) or listed as one no "
                        + "request body writes to — a new one must be considered, not defaulted")
                .allSatisfy(qualified -> assertThat(claimed.contains(qualified)
                        || UNBOUND_BY_DESIGN.containsKey(qualified))
                        .as("%s is neither bounded by a DTO nor excused in UNBOUND_BY_DESIGN", qualified)
                        .isTrue());
    }

    /**
     * Every {@code table.column} created as a {@code VARCHAR(n)}, mapped to that {@code n}.
     *
     * <p>Reads the migrations in filename order so a later {@code ALTER} of a width would need to be
     * handled here too; today none exists, and this test failing is how the next one gets noticed.
     */
    private static Map<String, Integer> boundedColumns() {
        Map<String, Integer> columns = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<Path> ordered = files.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
            for (Path file : ordered) {
                String table = null;
                for (String line : Files.readAllLines(file)) {
                    Matcher create = CREATE_TABLE.matcher(line);
                    if (create.find()) {
                        table = create.group(1).toLowerCase();
                        continue;
                    }
                    if (table == null) continue;
                    Matcher column = COLUMN.matcher(line);
                    if (column.find()) {
                        columns.put(table + "." + column.group(1).toLowerCase(),
                                Integer.parseInt(column.group(2)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the migrations at " + MIGRATIONS.toAbsolutePath(), e);
        }
        assertThat(columns)
                .as("no bounded columns were parsed out of %s — the migrations moved, or their shape "
                        + "changed and this test is now reading nothing", MIGRATIONS.toAbsolutePath())
                .isNotEmpty();
        return columns;
    }
}
