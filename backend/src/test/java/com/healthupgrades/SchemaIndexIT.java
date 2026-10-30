package com.healthupgrades;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.PostgresIT;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The indexes the migrations leave, read from PostgreSQL's catalogue once Flyway has run them all.
 *
 * <p>Two rules, each about every table rather than one:
 *
 * <ul>
 *   <li><strong>A foreign key's referencing column leads an index</strong> (ADR-063, Decision 3). A
 *       {@code REFERENCES} clause creates none, and on every delete of a parent row PostgreSQL's
 *       referential-integrity trigger looks the children up by that column. Without an index that is a
 *       scan of the whole child table. A partial index on {@code column IS NOT NULL} counts: the
 *       trigger's {@code column = $1} implies it.</li>
 *   <li><strong>No index repeats the leading columns of another.</strong> An index that is a prefix of
 *       another serves no query the longer one does not, and every write still pays to maintain it. A
 *       unique index is exempt, since it enforces something, and so is a partial one, which covers
 *       fewer rows.</li>
 * </ul>
 *
 * <p>An {@code *IT} because the catalogue is the only place both rules can be read whole: a unique
 * constraint's index has no {@code CREATE INDEX} in any migration.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaIndexIT extends PostgresIT {

    /** Every valid index in the migrated schema, with its key columns in order; an expression is null. */
    private static final String INDEXES = """
            SELECT t.relname AS table_name,
                   ic.relname AS index_name,
                   i.indisunique AS is_unique,
                   pg_get_expr(i.indpred, i.indrelid) AS predicate,
                   ARRAY(SELECT a.attname
                         FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, position)
                         LEFT JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                         WHERE k.position <= i.indnkeyatts
                         ORDER BY k.position)::text[] AS columns
            FROM pg_index i
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_class t ON t.oid = i.indrelid
            WHERE t.relnamespace = current_schema()::regnamespace
              AND i.indisvalid
            """;

    /** Every foreign key in the migrated schema, with its referencing columns in order. */
    private static final String FOREIGN_KEYS = """
            SELECT t.relname AS table_name,
                   c.conname AS constraint_name,
                   ARRAY(SELECT a.attname
                         FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, position)
                         JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                         ORDER BY k.position)::text[] AS columns
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            WHERE c.contype = 'f'
              AND t.relnamespace = current_schema()::regnamespace
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void GivenTheMigratedSchema_WhenEachForeignKeyIsChecked_ThenAnIndexLeadsWithItsReferencingColumn() {
        List<Index> indexes = indexes();
        List<ForeignKey> foreignKeys = jdbc.query(FOREIGN_KEYS, SchemaIndexIT::foreignKey);

        assertThat(foreignKeys).as("the catalogue query found no foreign keys").isNotEmpty();
        assertThat(foreignKeys)
                .as("a foreign key whose referencing column leads no index, so deleting a parent scans the "
                        + "whole child table (ADR-063)")
                .filteredOn(foreignKey -> indexes.stream().noneMatch(index -> index.serves(foreignKey)))
                .isEmpty();
    }

    @Test
    void GivenTheMigratedSchema_WhenEachIndexIsComparedWithTheOthers_ThenNoneRepeatsTheLeadingColumnsOfAnother() {
        List<Index> indexes = indexes();

        assertThat(indexes)
                .as("an index whose columns lead another index on the same table, so it serves nothing the "
                        + "other does not")
                .filteredOn(index -> indexes.stream().anyMatch(index::isMadeRedundantBy))
                .isEmpty();
    }

    private List<Index> indexes() {
        List<Index> indexes = jdbc.query(INDEXES, SchemaIndexIT::index);
        assertThat(indexes).as("the catalogue query found no indexes").isNotEmpty();
        return indexes;
    }

    private static Index index(ResultSet row, int rowNumber) throws SQLException {
        return new Index(
                row.getString("table_name"),
                row.getString("index_name"),
                row.getBoolean("is_unique"),
                row.getString("predicate"),
                Arrays.asList((String[]) row.getArray("columns").getArray()));
    }

    private static ForeignKey foreignKey(ResultSet row, int rowNumber) throws SQLException {
        return new ForeignKey(row.getString("table_name"), row.getString("constraint_name"), Arrays.asList((String[])
                row.getArray("columns").getArray()));
    }

    /** A foreign key, by the table that holds it and the columns that reference the parent. */
    private record ForeignKey(String table, String name, List<String> columns) {}

    /**
     * An index, by its table, its key columns in order (an expression's is null), and the predicate of a
     * partial one.
     */
    private record Index(
            String table,
            String name,
            boolean unique,
            @Nullable String predicate,
            List<@Nullable String> columns) {

        /** Whether the trigger behind this foreign key can look its rows up through this index. */
        boolean serves(ForeignKey foreignKey) {
            String leading = foreignKey.columns().getFirst();
            return table.equals(foreignKey.table())
                    && leading.equals(columns.getFirst())
                    && (predicate == null || predicate.equals("(" + leading + " IS NOT NULL)"));
        }

        /** Whether another index on the same table starts with every column of this one. */
        boolean isMadeRedundantBy(Index other) {
            return !unique
                    && predicate == null
                    && other.predicate() == null
                    && !name.equals(other.name())
                    && table.equals(other.table())
                    && columns.size() <= other.columns().size()
                    && !columns.contains(null)
                    && columns.equals(other.columns().subList(0, columns.size()));
        }
    }
}
