package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How {@link FlywayMigrations} reads a migration directory, on fixture directories.
 *
 * <p>Flyway scans a location recursively, so a script in a folder beneath {@code db/migration} runs on every
 * database, while every reader of the migrations, these contract tests and the ER diagram's generator alike,
 * looks at the top level alone. A nested migration would escape all of them, and a nested callback would run
 * in every deployment (#84). So every migration sits directly in the directory, and a nested script of any
 * kind is refused.
 */
class FlywayMigrationsTest {

    private static final String ANY_SQL = "SELECT 1;";

    @TempDir
    Path directory;

    /** The ordinary case: each migration at the top level, read in Flyway's order, not the name's. */
    @Test
    void GivenMigrationsDirectlyInTheDirectory_WhenTheMigrationsAreRead_ThenEachIsReadInVersionOrder()
            throws IOException {
        write(directory.resolve("V10__tenth.sql"));
        write(directory.resolve("V2__second.sql"));

        assertThat(FlywayMigrations.read(directory))
                .map(FlywayMigrations.Migration::fileName)
                .containsExactly("V2__second.sql", "V10__tenth.sql");
    }

    /**
     * A well-named migration in a subfolder runs on every database, but the ER diagram's generator never
     * reads it, so its DDL would be missing from the diagram while the diagram check passed.
     */
    @Test
    void GivenAVersionedMigrationInAFolderBeneathTheDirectory_WhenTheMigrationsAreRead_ThenItIsRefused()
            throws IOException {
        write(directory.resolve("V1__first.sql"));
        write(directory.resolve("nested").resolve("V2__second.sql"));

        assertThatThrownBy(() -> FlywayMigrations.read(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("V2__second.sql");
    }

    /** With no callback location named, Flyway finds callbacks among the migrations and runs them everywhere. */
    @Test
    void GivenACallbackInAFolderBeneathTheDirectory_WhenTheMigrationsAreRead_ThenItIsRefused() throws IOException {
        write(directory.resolve("V1__first.sql"));
        write(directory.resolve("demo").resolve("afterMigrate__demo_account.sql"));

        assertThatThrownBy(() -> FlywayMigrations.read(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterMigrate__demo_account.sql");
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, ANY_SQL);
    }
}
