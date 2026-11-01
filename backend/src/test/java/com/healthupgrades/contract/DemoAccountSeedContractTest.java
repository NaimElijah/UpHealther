package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.RepositoryVersion;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Holds the demo account's seed to the development side of the repository (#84).
 *
 * <p>The account has a published password, so its script lives in {@code backend/config/db/demo}, which
 * neither the jar nor the image contains, and runs only where a Flyway callback location names that folder:
 * {@code backend/config/application.yml} for a run from {@code backend/}, and the compose backend, which
 * mounts the folder, for the local stack. Lose either line and nothing fails: that run simply starts with no
 * demo account, and the README's sign-in stops working.
 *
 * <p>The other side is what keeps a deployment safe. A callback location copied into one names a folder it
 * does not have, which Flyway skips; that holds only while the jar and the image carry no seed of their
 * own, and while no production code reads the seed's setting or the account as a sign of where it runs.
 * {@code ShippedFlywayConfigurationTest} holds the jar's configuration to naming no callback location.
 *
 * <p>Files are read from the root {@link RepositoryVersion#repositoryRoot()} finds, so it runs the same from
 * Maven and from an IDE.
 */
class DemoAccountSeedContractTest {

    /** The seed's folder, relative to the repository root. */
    private static final String SEED_DIRECTORY = "backend/config/db/demo";

    /** The configuration a run from {@code backend/} reads, relative to the repository root. */
    private static final String CHECKOUT_CONFIGURATION = "backend/config/application.yml";

    /** How that configuration names the seed: relative to {@code backend/}, the run's working directory. */
    private static final String CHECKOUT_LOCATION = "filesystem:config/db/demo";

    /** The developer stack, relative to the repository root. */
    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** Where the compose backend mounts the seed's folder. */
    private static final String COMPOSE_MOUNT_POINT = "/demo-seed";

    /** How the compose backend names the mounted seed. */
    private static final String COMPOSE_LOCATION = "filesystem:" + COMPOSE_MOUNT_POINT;

    /** The compose backend's mount of the seed: the folder, relative to the compose file, read-only. */
    private static final String COMPOSE_SEED_MOUNT = "./" + SEED_DIRECTORY + ":" + COMPOSE_MOUNT_POINT + ":ro";

    /** The variable Boot binds to {@code spring.flyway.callback-locations}. */
    private static final String CALLBACK_LOCATIONS_VARIABLE = "SPRING_FLYWAY_CALLBACK_LOCATIONS";

    /**
     * A Flyway SQL callback's file name, capturing its event: {@code afterMigrate__x.sql} and
     * {@code afterMigrate.sql} are both afterMigrate, since Flyway takes a callback with no description too.
     */
    private static final Pattern CALLBACK_SCRIPT = Pattern.compile("^(\\w+?)(?:__\\w+)?\\.sql$");

    /** What the jar is built from, relative to the repository root. */
    private static final String JAR_RESOURCES = "backend/src/main/resources";

    /** The one folder of the jar's resources that may hold SQL, relative to {@link #JAR_RESOURCES}. */
    private static final String MIGRATIONS = "db/migration";

    /** The production code, relative to the repository root. */
    private static final String PRODUCTION_SOURCES = "backend/src/main/java";

    /**
     * What production code must never name: the seed's folder, the setting that turns it on in either
     * spelling, and the id the seed gives the account.
     */
    private static final List<String> SEED_SIGNALS =
            List.of("db/demo", "callback-locations", "callbackLocations", "a0000000-0000-0000-0000-000000000001");

    /** The image's build file, relative to the repository root. */
    private static final String DOCKERFILE = "backend/Dockerfile";

    /** A Dockerfile instruction that puts files into the image. */
    private static final Pattern FILE_INSTRUCTION = Pattern.compile("^(COPY|ADD)\\s", Pattern.CASE_INSENSITIVE);

    /** The runtime stage's one permitted copy: the jar, out of the build stage. */
    private static final Pattern COPIES_THE_JAR_ALONE =
            Pattern.compile("^COPY\\s+--from=\\S+\\s+\\S+\\.jar\\s+\\S+\\.jar$", Pattern.CASE_INSENSITIVE);

    /**
     * Without this line a run from {@code backend/} has no demo account. The location is relative, so it
     * resolves only where that run's working directory is {@code backend/}.
     */
    @Test
    void GivenTheCheckoutsConfiguration_WhenItsFlywaySettingsAreRead_ThenItNamesTheSeedFolderForCallbacks() {
        Map<?, ?> spring = mapping(
                mapping(load(CHECKOUT_CONFIGURATION), CHECKOUT_CONFIGURATION).get("spring"), "spring");
        Map<?, ?> flyway = mapping(spring.get("flyway"), "spring.flyway");

        assertThat(flyway.get("callback-locations"))
                .as(
                        "%s must name %s as spring.flyway.callback-locations, or a run from backend/ has no demo"
                                + " account",
                        CHECKOUT_CONFIGURATION, CHECKOUT_LOCATION)
                .isEqualTo(CHECKOUT_LOCATION);
    }

    /**
     * Without these lines the local stack has no demo account. A literal rather than a {@code ${...}}, so no
     * {@code .env} can empty or redirect it, and read-only, so nothing the backend runs can rewrite a script
     * every later start executes.
     */
    @Test
    void GivenTheComposeBackend_WhenItsEnvironmentAndMountsAreRead_ThenItMountsTheSeedReadOnlyAndNamesTheMount() {
        Map<?, ?> backend = composeBackend();
        Map<?, ?> environment = mapping(backend.get("environment"), "services.backend.environment");

        assertThat(environment.get(CALLBACK_LOCATIONS_VARIABLE))
                .as(
                        "the compose backend must set %s to the literal %s, or the local stack has no demo account",
                        CALLBACK_LOCATIONS_VARIABLE, COMPOSE_LOCATION)
                .isEqualTo(COMPOSE_LOCATION);
        assertThat(strings(backend, "volumes"))
                .as("the compose backend must mount the seed read-only: %s", COMPOSE_SEED_MOUNT)
                .contains(COMPOSE_SEED_MOUNT);
    }

    /**
     * A callback location is read for callbacks and nothing else, so a versioned or repeatable migration put
     * there would never run anywhere, and its author would believe it had.
     */
    @Test
    void GivenTheSeedFolder_WhenItsFilesAreListed_ThenEachIsAFlywayCallbackScript() {
        List<String> fileNames = regularFilesUnder(SEED_DIRECTORY).stream()
                .map(file -> file.getFileName().toString())
                .toList();

        assertThat(fileNames)
                .as("%s holds no file; finding none means this test is reading nothing", SEED_DIRECTORY)
                .isNotEmpty();
        assertThat(fileNames)
                .as(
                        "every file in %s must be a Flyway SQL callback, <event>[__<name>].sql: a callback location"
                                + " is read for nothing else, so a V or R migration there never runs",
                        SEED_DIRECTORY)
                .allMatch(DemoAccountSeedContractTest::isCallbackScript);
    }

    /**
     * A script anywhere in the jar's resources is one setting away from running in every deployment: Boot
     * reads callbacks from a classpath location as readily as from a folder. Only the migrations belong
     * there, and a migration cannot recreate the account, because V21 deletes it.
     */
    @Test
    void GivenTheJarsResources_WhenEverySqlFileIsListed_ThenEachIsUnderTheMigrationFolder() {
        Path resources = RepositoryVersion.repositoryRoot().resolve(JAR_RESOURCES);
        Path migrations = resources.resolve(MIGRATIONS);

        List<Path> sqlFiles = regularFilesUnder(JAR_RESOURCES).stream()
                .filter(file ->
                        file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                .toList();

        assertThat(sqlFiles)
                .as("no SQL file found under %s; finding none means this test is reading nothing", JAR_RESOURCES)
                .isNotEmpty();
        assertThat(sqlFiles)
                .as(
                        "SQL files the jar would carry outside %s, where the demo seed or another like it could hide",
                        MIGRATIONS)
                .allMatch(file -> file.startsWith(migrations));
    }

    /**
     * The image is what a deployment runs. Its runtime stage copying anything beside the jar could carry the
     * seed, or {@code backend/config/} with the development signing key (ADR-042), into it.
     */
    @Test
    void GivenTheBackendDockerfile_WhenItsRuntimeStageIsRead_ThenItCopiesTheJarAndNothingElse() {
        List<String> runtimeCopies = fileInstructionsOfTheLastStage(read(DOCKERFILE));

        assertThat(runtimeCopies)
                .as("%s's runtime stage must copy the jar, and only the jar", DOCKERFILE)
                .singleElement()
                .matches(copy -> COPIES_THE_JAR_ALONE.matcher(copy).matches(), "copies the jar out of the build");
    }

    /**
     * A production class that read the seed's setting, or looked for the account, to decide that it runs
     * locally would turn a development convenience into a signal about the environment, which ADR-041 and
     * ADR-042 both refuse to rely on.
     */
    @Test
    void GivenTheProductionSources_WhenEachIsRead_ThenNoneNamesTheSeedItsSettingOrTheDemoAccountsId() {
        List<String> offenders = new ArrayList<>();
        List<Path> sources = regularFilesUnder(PRODUCTION_SOURCES);
        for (Path source : sources) {
            String text = readFile(source);
            SEED_SIGNALS.stream()
                    .filter(text::contains)
                    .forEach(signal -> offenders.add(source.getFileName() + " names " + signal));
        }

        assertThat(sources)
                .as("no source found under %s; finding none means this test is reading nothing", PRODUCTION_SOURCES)
                .isNotEmpty();
        assertThat(offenders)
                .as("production code must not depend on the demo seed (#84)")
                .isEmpty();
    }

    /** The rule {@link #GivenTheSeedFolder_WhenItsFilesAreListed_ThenEachIsAFlywayCallbackScript} applies. */
    @Test
    void GivenScriptNames_WhenEachIsCheckedForACallback_ThenOnlyOneNamedForAFlywayEventPasses() {
        assertThat(isCallbackScript("afterMigrate__demo_account.sql")).isTrue();
        assertThat(isCallbackScript("afterMigrate.sql")).isTrue();
        assertThat(isCallbackScript("V21__demo_account.sql")).isFalse();
        assertThat(isCallbackScript("V21.sql")).isFalse();
        assertThat(isCallbackScript("R__demo_account.sql")).isFalse();
        assertThat(isCallbackScript("afterMigrateNow__demo_account.sql")).isFalse();
        assertThat(isCallbackScript("afterMigrate__demo_account.txt")).isFalse();
    }

    /** The rule the Dockerfile case applies, on fixture Dockerfiles. */
    @Test
    void GivenDockerfiles_WhenTheirLastStageIsRead_ThenOnlyItsCopiesAreReturnedWithContinuationsJoined() {
        assertThat(fileInstructionsOfTheLastStage("""
                FROM jdk AS build
                COPY src ./src
                FROM jre
                # COPY config ./config
                COPY --from=build \\
                    /app/target/*.jar app.jar
                ADD config ./config
                """))
                .containsExactly("COPY --from=build /app/target/*.jar app.jar", "ADD config ./config");
    }

    /** Whether Flyway would read the file as an SQL callback: its prefix is the id of one of Flyway's events. */
    private static boolean isCallbackScript(String fileName) {
        Matcher callback = CALLBACK_SCRIPT.matcher(fileName);
        return callback.matches() && Event.fromId(callback.group(1)) != null;
    }

    /**
     * Every {@code COPY} and {@code ADD} after the Dockerfile's last {@code FROM}, each on one line with its
     * continuations joined and its whitespace collapsed; comment lines are dropped.
     */
    private static List<String> fileInstructionsOfTheLastStage(String dockerfile) {
        List<String> instructions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : dockerfile.lines().toList()) {
            String stripped = line.strip();
            if (current.isEmpty() && (stripped.isEmpty() || stripped.startsWith("#"))) {
                continue;
            }
            if (stripped.endsWith("\\")) {
                current.append(stripped, 0, stripped.length() - 1).append(' ');
                continue;
            }
            instructions.add(current.append(stripped).toString().strip().replaceAll("\\s+", " "));
            current.setLength(0);
        }

        List<String> lastStage = new ArrayList<>();
        for (String instruction : instructions) {
            if (instruction.regionMatches(true, 0, "FROM ", 0, "FROM ".length())) {
                lastStage.clear();
            } else if (FILE_INSTRUCTION.matcher(instruction).find()) {
                lastStage.add(instruction);
            }
        }
        return lastStage;
    }

    private static Map<?, ?> composeBackend() {
        Map<?, ?> services = mapping(mapping(load(COMPOSE_FILE), COMPOSE_FILE).get("services"), "services");
        return mapping(services.get("backend"), "services.backend");
    }

    /** A sequence under a service's key, each entry as a string; empty when the key is absent. */
    private static List<String> strings(Map<?, ?> service, String key) {
        Object node = service.get(key);
        if (node == null) {
            return List.of();
        }
        assertThat(node).as("%s is not a sequence", key).isInstanceOf(List.class);
        return ((List<?>) node).stream().map(String::valueOf).toList();
    }

    /** Every regular file under a directory, its subfolders included, as Flyway scans one. */
    private static List<Path> regularFilesUnder(String relativePath) {
        Path directory = RepositoryVersion.repositoryRoot().resolve(relativePath);
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + directory, e);
        }
    }

    private static Map<?, ?> mapping(Object node, String path) {
        assertThat(node).as("%s is not a mapping", path).isInstanceOf(Map.class);
        return (Map<?, ?>) node;
    }

    /** A file under the repository root, as text. */
    private static String read(String relativePath) {
        return readFile(RepositoryVersion.repositoryRoot().resolve(relativePath));
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /**
     * A YAML file under the repository root, parsed. The safe constructor builds only maps, lists and
     * scalars, so nothing in the file can make the parser instantiate a class.
     */
    private static Object load(String relativePath) {
        Path file = RepositoryVersion.repositoryRoot().resolve(relativePath);
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }
}
