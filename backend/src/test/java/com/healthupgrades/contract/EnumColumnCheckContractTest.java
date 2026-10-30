package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Pins the value list of every enum column's {@code CHECK} constraint against the Java enum it stores.
 *
 * <p>The set of values is written twice, once as the enum's constants and once in a migration
 * (ADR-073). Nothing else connects the two: Hibernate's {@code validate} checks a column's type, never
 * its constraints. So a constant added to the enum without a migration would be refused by the database
 * on its first write, as a 500, and a constant removed would leave rows the application can no longer
 * read.
 *
 * <p>The enum columns are found from the entities, every {@code @Enumerated} field, rather than listed
 * here, so a new one fails this test until a migration gives it a {@code CHECK}.
 */
class EnumColumnCheckContractTest {

    /** The package the entities are scanned from. */
    private static final String ENTITY_PACKAGE = "com.healthupgrades";

    /** The {@code CREATE TABLE users} that opens a statement creating a table. */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_]+)", Pattern.CASE_INSENSITIVE);

    /** The {@code ALTER TABLE users} that opens a statement changing a table. */
    private static final Pattern ALTER_TABLE =
            Pattern.compile("ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?([a-z_]+)", Pattern.CASE_INSENSITIVE);

    /**
     * A value-list check, {@code CHECK (column IN ('A', 'B'))}, optionally named by the
     * {@code [ADD] CONSTRAINT name} before it.
     */
    private static final Pattern VALUE_LIST_CHECK = Pattern.compile(
            "(?:CONSTRAINT\\s+(?<name>[a-z_]+)\\s+)?CHECK\\s*\\(\\s*(?<column>[a-z_]+)\\s+IN\\s*\\((?<values>[^)]*)\\)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /** A {@code DROP CONSTRAINT name} clause, which takes a named check away again. */
    private static final Pattern DROP_CONSTRAINT =
            Pattern.compile("\\bDROP\\s+CONSTRAINT\\s+(?:IF\\s+EXISTS\\s+)?(?<name>[a-z_]+)", Pattern.CASE_INSENSITIVE);

    /** One quoted value in a value list. */
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    /** A {@code CHECK} anywhere in a statement, to catch one this parser would otherwise skip. */
    private static final Pattern ANY_CHECK = Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** Every {@code @Enumerated} field of every entity, as the column it maps to and the enum it stores. */
    static Stream<EnumColumn> enumColumns() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return scanner.findCandidateComponents(ENTITY_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(EnumColumnCheckContractTest::load)
                .flatMap(entity -> Arrays.stream(entity.getDeclaredFields())
                        .filter(field -> field.isAnnotationPresent(Enumerated.class))
                        .map(field -> EnumColumn.of(entity, field)));
    }

    @Test
    void GivenTheEntities_WhenTheirEnumColumnsAreListed_ThenTheKnownOnesAreAmongThem() {
        // Otherwise a scan that found nothing would pass the parameterised case below for nothing.
        assertThat(enumColumns().map(EnumColumn::qualified))
                .contains("health_upgrades.status", "notifications.type", "users.role");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enumColumns")
    void GivenAnEnumColumn_WhenItsCheckIsReadFromTheMigrations_ThenItAllowsExactlyTheEnumsConstants(EnumColumn column) {
        List<String> allowed = valueListChecks(FlywayMigrations.read()).get(column.qualified());

        assertThat(column.stored())
                .as("%s is stored by name, which is what its CHECK lists", column)
                .isEqualTo(EnumType.STRING);
        assertThat(allowed)
                .as("no migration gives %s a CHECK (%s IN (...)) constraint (ADR-073)", column, column.column())
                .isNotNull();
        assertThat(allowed)
                .as(
                        "the CHECK on %s and the constants of %s differ: a constant the CHECK lacks is refused on"
                                + " its first write, and a value it keeps that the enum lost cannot be read back",
                        column, column.type().getSimpleName())
                .containsExactlyInAnyOrderElementsOf(column.constants());
    }

    // ---- the parser, on fixture SQL ----

    @Test
    void GivenACheckAddedByAlterTable_WhenTheMigrationsAreParsed_ThenItsValuesAreRead() {
        Map<String, List<String>> checks = valueListChecks(Map.of("V1__x.sql", """
                ALTER TABLE health_upgrades
                    ADD CONSTRAINT health_upgrades_status_check CHECK (status IN ('IDEA', 'PLANNED')) NOT VALID;
                """));

        assertThat(checks).containsExactlyEntriesOf(Map.of("health_upgrades.status", List.of("IDEA", "PLANNED")));
    }

    @Test
    void GivenACheckInsideCreateTable_WhenTheMigrationsAreParsed_ThenItsValuesAreRead() {
        Map<String, List<String>> checks = valueListChecks(Map.of("V1__x.sql", """
                CREATE TABLE users (
                    id UUID PRIMARY KEY,
                    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ADMIN'))
                );
                """));

        assertThat(checks).containsEntry("users.role", List.of("USER", "ADMIN"));
    }

    @Test
    void GivenACheckDroppedAndReplacedByALaterMigration_WhenTheMigrationsAreParsed_ThenTheReplacementWins() {
        Map<String, List<String>> checks = valueListChecks(Map.of(
                "V1__x.sql",
                "ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER'));",
                "V2__y.sql",
                """
                ALTER TABLE users DROP CONSTRAINT users_role_check;
                ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
                """));

        assertThat(checks).containsEntry("users.role", List.of("USER", "ADMIN"));
    }

    @Test
    void GivenACheckDroppedAndNotReplaced_WhenTheMigrationsAreParsed_ThenTheColumnHasNone() {
        Map<String, List<String>> checks = valueListChecks(Map.of(
                "V1__x.sql",
                "ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER'));",
                "V2__y.sql",
                "ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;"));

        assertThat(checks).doesNotContainKey("users.role");
    }

    @Test
    void GivenACheckOnlyInAComment_WhenTheMigrationsAreParsed_ThenItIsIgnored() {
        Map<String, List<String>> checks = valueListChecks(
                Map.of("V1__x.sql", "-- ALTER TABLE users ADD CONSTRAINT c CHECK (role IN ('USER'));\nSELECT 1;"));

        assertThat(checks).isEmpty();
    }

    @Test
    void GivenACheckOutsideATableStatement_WhenTheMigrationsAreParsed_ThenItIsRefusedRatherThanSkipped() {
        Map<String, String> migrations = Map.of("V1__x.sql", """
                DO $$
                BEGIN
                    ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER'));
                END $$;
                """);

        assertThatThrownBy(() -> valueListChecks(migrations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("V1__x.sql");
    }

    private static Map<String, List<String>> valueListChecks(Map<String, String> sqlByFileName) {
        return valueListChecks(FlywayMigrations.inVersionOrder(sqlByFileName));
    }

    /**
     * Every value-list check the migrations leave, as {@code table.column} to its values in the order
     * written, once every migration has run in Flyway's order.
     *
     * @throws IllegalArgumentException if a statement holds a {@code CHECK} but does not start with
     *     {@code CREATE TABLE} or {@code ALTER TABLE}, as one inside a {@code DO} block does not
     */
    private static Map<String, List<String>> valueListChecks(List<FlywayMigrations.Migration> migrations) {
        Map<String, String> columnByConstraint = new LinkedHashMap<>();
        Map<String, List<String>> valuesByColumn = new LinkedHashMap<>();
        for (FlywayMigrations.Migration migration : migrations) {
            for (String statement : migration.statements()) {
                Matcher table = CREATE_TABLE.matcher(statement);
                if (!table.lookingAt()) {
                    table = ALTER_TABLE.matcher(statement);
                }
                if (!table.lookingAt()) {
                    if (ANY_CHECK.matcher(statement).find()) {
                        throw new IllegalArgumentException(migration.fileName()
                                + " has a CHECK outside a CREATE TABLE or ALTER TABLE statement, which this"
                                + " parser cannot read: " + statement);
                    }
                    continue;
                }
                String tableName = table.group(1).toLowerCase();
                Matcher dropped = DROP_CONSTRAINT.matcher(statement);
                while (dropped.find()) {
                    String column =
                            columnByConstraint.remove(dropped.group("name").toLowerCase());
                    if (column != null) {
                        valuesByColumn.remove(column);
                    }
                }
                Matcher check = VALUE_LIST_CHECK.matcher(statement);
                while (check.find()) {
                    String column = tableName + "." + check.group("column").toLowerCase();
                    if (check.group("name") != null) {
                        columnByConstraint.put(check.group("name").toLowerCase(), column);
                    }
                    valuesByColumn.put(
                            column,
                            QUOTED.matcher(check.group("values"))
                                    .results()
                                    .map(value -> value.group(1))
                                    .toList());
                }
            }
        }
        return valuesByColumn;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the scan named an entity that cannot be loaded: " + className, e);
        }
    }

    /**
     * An enum-valued column, as its entity maps it.
     *
     * @param table  the entity's {@code @Table} name
     * @param column the column, named by {@code @Column} or, as Boot's naming strategy does, by the field
     *     in snake case
     * @param type   the enum it stores
     * @param stored how it is stored
     */
    record EnumColumn(String table, String column, Class<?> type, EnumType stored) {

        static EnumColumn of(Class<?> entity, Field field) {
            Column mapping = field.getAnnotation(Column.class);
            String column = mapping != null && !mapping.name().isEmpty()
                    ? mapping.name()
                    : field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
            return new EnumColumn(
                    entity.getAnnotation(Table.class).name(),
                    column,
                    field.getType(),
                    field.getAnnotation(Enumerated.class).value());
        }

        String qualified() {
            return table + "." + column;
        }

        List<String> constants() {
            return Arrays.stream(type.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .toList();
        }

        @Override
        public String toString() {
            return qualified();
        }
    }
}
