package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.RepositoryVersion;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * Holds the two places that seed the demo account in a development run to the one location that holds its
 * script, and that location to callbacks alone (#84).
 *
 * <p>The account has a published password, so it exists only where something names {@code db/demo} as a
 * Flyway callback location: {@code backend/config/application.yml} for a run from {@code backend/}, and the
 * compose backend's environment for the local stack. Lose either line and nothing fails: that run simply
 * starts with no demo account, and the README's sign-in stops working. {@code ShippedFlywayConfigurationTest}
 * holds the other side, that the jar names no such location.
 *
 * <p>A callback location is read for callbacks and nothing else, so a versioned or repeatable migration put
 * there would never run anywhere, and its author would believe it had. Every file there must be a script
 * named for a Flyway event.
 *
 * <p>Files are read from the root {@link RepositoryVersion#repositoryRoot()} finds, so it runs the same from
 * Maven and from an IDE.
 */
class DemoAccountSeedContractTest {

    /** The callback location holding the demo account's script, as Spring and Flyway name it. */
    private static final String DEMO_LOCATION = "classpath:db/demo";

    /** That location in the source tree, relative to the repository root. */
    private static final String DEMO_DIRECTORY = "backend/src/main/resources/db/demo";

    /** The configuration a run from {@code backend/} reads, relative to the repository root. */
    private static final String CHECKOUT_CONFIGURATION = "backend/config/application.yml";

    /** The developer stack, relative to the repository root. */
    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** The variable Boot binds to {@code spring.flyway.callback-locations}. */
    private static final String CALLBACK_LOCATIONS_VARIABLE = "SPRING_FLYWAY_CALLBACK_LOCATIONS";

    /** A Flyway SQL callback's file name, capturing its event: {@code afterMigrate__x.sql} is afterMigrate. */
    private static final Pattern CALLBACK_SCRIPT = Pattern.compile("^(\\w+?)__\\w+\\.sql$");

    @Test
    void GivenTheCheckoutsConfiguration_WhenItsFlywaySettingsAreRead_ThenItNamesTheDemoLocationForCallbacks() {
        Map<?, ?> flyway = mapping(
                mapping(
                                mapping(load(CHECKOUT_CONFIGURATION), CHECKOUT_CONFIGURATION)
                                        .get("spring"),
                                "spring")
                        .get("flyway"),
                "spring.flyway");

        assertThat(flyway.get("callback-locations"))
                .as(
                        "%s must name %s as spring.flyway.callback-locations, or a run from backend/ has no demo"
                                + " account",
                        CHECKOUT_CONFIGURATION, DEMO_LOCATION)
                .isEqualTo(DEMO_LOCATION);
    }

    @Test
    void GivenTheComposeBackend_WhenItsEnvironmentIsRead_ThenItNamesTheDemoLocationForCallbacksAsALiteral() {
        Map<?, ?> services = mapping(mapping(load(COMPOSE_FILE), COMPOSE_FILE).get("services"), "services");
        Map<?, ?> backend = mapping(services.get("backend"), "services.backend");
        Map<?, ?> environment = mapping(backend.get("environment"), "services.backend.environment");

        // Equal to the literal, so a ${...} that a .env could empty or redirect fails here too.
        assertThat(environment.get(CALLBACK_LOCATIONS_VARIABLE))
                .as(
                        "the compose backend must set %s to %s, or the local stack has no demo account",
                        CALLBACK_LOCATIONS_VARIABLE, DEMO_LOCATION)
                .isEqualTo(DEMO_LOCATION);
    }

    @Test
    void GivenTheDemoLocation_WhenItsFilesAreListed_ThenEachIsAFlywayCallbackScript() {
        List<String> fileNames = fileNamesUnder(DEMO_DIRECTORY);

        assertThat(fileNames)
                .as("%s holds no file; finding none means this test is reading nothing", DEMO_DIRECTORY)
                .isNotEmpty();
        assertThat(fileNames)
                .as(
                        "every file in %s must be a Flyway SQL callback, <event>__<name>.sql: a callback location"
                                + " is read for nothing else, so a V or R migration there never runs",
                        DEMO_DIRECTORY)
                .allMatch(DemoAccountSeedContractTest::isCallbackScript);
    }

    @Test
    void GivenScriptNames_WhenEachIsCheckedForACallback_ThenOnlyOneNamedForAFlywayEventPasses() {
        assertThat(isCallbackScript("afterMigrate__demo_account.sql")).isTrue();
        assertThat(isCallbackScript("V21__demo_account.sql")).isFalse();
        assertThat(isCallbackScript("R__demo_account.sql")).isFalse();
        assertThat(isCallbackScript("afterMigrateNow__demo_account.sql")).isFalse();
        assertThat(isCallbackScript("afterMigrate__demo_account.txt")).isFalse();
    }

    /** Whether Flyway would read the file as an SQL callback: its prefix is the id of one of Flyway's events. */
    private static boolean isCallbackScript(String fileName) {
        Matcher callback = CALLBACK_SCRIPT.matcher(fileName);
        return callback.matches() && Event.fromId(callback.group(1)) != null;
    }

    /** The name of every regular file under a directory, its subfolders included, as Flyway scans one. */
    private static List<String> fileNamesUnder(String relativePath) {
        Path directory = RepositoryVersion.repositoryRoot().resolve(relativePath);
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + directory, e);
        }
    }

    private static Map<?, ?> mapping(Object node, String path) {
        assertThat(node).as("%s is not a mapping", path).isInstanceOf(Map.class);
        return (Map<?, ?>) node;
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
