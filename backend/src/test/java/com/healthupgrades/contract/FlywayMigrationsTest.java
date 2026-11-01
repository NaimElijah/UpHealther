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
 * database. Every contract test that reads the migrations would miss it if only the top level were read: a
 * versioned one would escape their rules, and a callback there would run in every deployment (#84).
 */
class FlywayMigrationsTest {

    private static final String ANY_SQL = "SELECT 1;";

    @TempDir
    Path directory;

    @Test
    void GivenAVersionedMigrationInAFolderBeneathTheDirectory_WhenTheMigrationsAreRead_ThenItIsReadInItsPlace()
            throws IOException {
        write(directory.resolve("V1__first.sql"));
        write(directory.resolve("nested").resolve("V2__second.sql"));

        assertThat(FlywayMigrations.read(directory))
                .map(FlywayMigrations.Migration::fileName)
                .containsExactly("V1__first.sql", "V2__second.sql");
    }

    @Test
    void GivenACallbackInAFolderBeneathTheDirectory_WhenTheMigrationsAreRead_ThenItIsRefusedAsAMisnamedOneIs()
            throws IOException {
        write(directory.resolve("V1__first.sql"));
        write(directory.resolve("demo").resolve("afterMigrate__demo_account.sql"));

        assertThatThrownBy(() -> FlywayMigrations.read(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterMigrate__demo_account.sql");
    }

    @Test
    void GivenTwoMigrationsOfOneNameInDifferentFolders_WhenTheMigrationsAreRead_ThenTheyAreRefused()
            throws IOException {
        // Keyed by name, one would silently replace the other, and no rule would ever read it.
        write(directory.resolve("V1__first.sql"));
        write(directory.resolve("nested").resolve("V1__first.sql"));

        assertThatThrownBy(() -> FlywayMigrations.read(directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("V1__first.sql");
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, ANY_SQL);
    }
}
