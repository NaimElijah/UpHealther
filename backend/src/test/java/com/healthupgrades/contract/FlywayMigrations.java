package com.healthupgrades.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Flyway migrations, read as text, for the contract tests that hold another source of truth against
 * them.
 *
 * <p>A test reads the files rather than a migrated database, so it runs in {@code ./mvnw test} with no
 * Docker. What it cannot read is anything that only exists once PostgreSQL has run the SQL, such as a
 * constraint's generated name: a test of that is an {@code *IT}.
 */
final class FlywayMigrations {

    /**
     * Flyway's migration directory, relative to {@code backend/}, where Maven runs the tests. Every migration
     * sits directly in it: Flyway would also run one from a folder beneath it, but {@link #read()} refuses
     * one there.
     */
    static final Path DIRECTORY = Paths.get("src", "main", "resources", "db", "migration");

    /** A Flyway versioned migration's file name, capturing its version: {@code V10__x.sql} is 10. */
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.*\\.sql$");

    /** A line comment or a block comment, removed before the statements are split. */
    private static final Pattern COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private FlywayMigrations() {}

    /**
     * One migration file.
     *
     * @param fileName its name, e.g. {@code V10__auth_session_spent_tokens.sql}
     * @param version  its version, part by part: {@code V1_2__x.sql} is {@code [1, 2]}
     * @param sql      its whole text, comments included
     */
    record Migration(String fileName, List<Integer> version, String sql) {

        /**
         * Its statements in the order written, with every comment removed and the blank ones dropped.
         *
         * <p>Split on {@code ;} alone, so a {@code DO} block's body comes back in pieces. A caller that
         * must not misread one checks for it, as {@code ColumnBoundContractTest} does.
         */
        List<String> statements() {
            return Arrays.stream(COMMENT.matcher(sql).replaceAll("").split(";"))
                    .map(String::strip)
                    .filter(statement -> !statement.isEmpty())
                    .toList();
        }

        /** Whether its major version, the part before any {@code .} or {@code _}, is at least this. */
        boolean isAtLeast(int major) {
            return version.getFirst() >= major;
        }

        /** Its file name, which is how a parameterised test names the case. */
        @Override
        public String toString() {
            return fileName;
        }
    }

    /**
     * Every migration in {@link #DIRECTORY}, in the order Flyway applies them.
     *
     * @throws UncheckedIOException if the directory cannot be read
     * @throws IllegalArgumentException if a {@code .sql} file there is not a versioned migration, or one sits
     *     in a folder beneath it
     */
    static List<Migration> read() {
        return read(DIRECTORY);
    }

    /**
     * Every migration in the given directory, in the order Flyway applies them.
     *
     * <p>Searches the folders beneath it too, but only to refuse what it finds there. Flyway scans a location
     * recursively, so a script nested there would run on every database, a callback included (#84); and
     * every other reader of the migrations, the ER diagram's generator among them, reads the top level
     * alone, so a nested migration's DDL would be missing from what they check while they passed. One rule
     * everywhere: every migration sits directly in the directory.
     *
     * @param directory where the migrations are
     * @throws UncheckedIOException if the directory cannot be read
     * @throws IllegalArgumentException if a {@code .sql} file there is not a versioned migration, or one sits
     *     in a folder beneath it
     */
    static List<Migration> read(Path directory) {
        Map<String, String> sqlByFileName = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .toList()) {
                if (!file.getParent().equals(directory)) {
                    throw new IllegalArgumentException(directory.relativize(file)
                            + " sits in a folder beneath " + directory
                            + "; every migration belongs directly in it, where every reader looks");
                }
                sqlByFileName.put(file.getFileName().toString(), Files.readString(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the migrations at " + directory.toAbsolutePath(), e);
        }
        return inVersionOrder(sqlByFileName);
    }

    /**
     * The given migrations in Flyway's order: by version number rather than by name, so {@code V10} runs
     * after {@code V9}, though it sorts before {@code V2}.
     *
     * @param sqlByFileName each migration's file name and its SQL, in any order
     * @throws IllegalArgumentException if a file name is not a versioned migration's, whose place in the
     *     order is unknown
     */
    static List<Migration> inVersionOrder(Map<String, String> sqlByFileName) {
        List<Migration> migrations = new ArrayList<>();
        sqlByFileName.forEach((fileName, sql) -> migrations.add(new Migration(fileName, version(fileName), sql)));
        migrations.sort(Comparator.comparing(Migration::version, FlywayMigrations::compareVersions));
        return List.copyOf(migrations);
    }

    /** Orders two versions numerically and part by part; a prefix sorts first. */
    private static int compareVersions(List<Integer> left, List<Integer> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int byPart = Integer.compare(left.get(i), right.get(i));
            if (byPart != 0) {
                return byPart;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static List<Integer> version(String fileName) {
        Matcher versioned = VERSIONED.matcher(fileName);
        if (!versioned.matches()) {
            throw new IllegalArgumentException(fileName
                    + " is not a versioned migration (V<version>__<name>.sql), so its place in the order is unknown");
        }
        return Arrays.stream(versioned.group(1).split("[._]"))
                .map(Integer::valueOf)
                .toList();
    }
}
