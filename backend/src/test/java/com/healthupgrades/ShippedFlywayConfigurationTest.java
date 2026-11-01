package com.healthupgrades;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.RepositoryVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Pins where the jar's own configuration tells Flyway to look: the migrations, and nothing else (#84).
 *
 * <p>A Flyway callback location adds scripts that run after every migration, and the demo account, with a
 * password published in this repository, is created by one. Only a development run may name a callback
 * location, from configuration outside the jar. A deployment reads the jar's configuration and its own
 * environment, so the jar must name none, or every database it migrates could hold an account anyone can
 * sign in to; and it must name no migration location but its own, since Flyway also looks for callbacks
 * among the migrations when no callback location is set.
 *
 * <p>Two of the cases read the configuration as a deployment with no profile reads it, the way
 * {@code ShippedJwtConfigurationTest} reads it: {@code spring.config.location} names the shipped file alone,
 * so configuration outside the jar is out of reach, and the process environment and system properties are
 * removed, so whatever the shell running the build exports cannot decide the outcome. That reading sees only
 * the documents no profile guards. A deployment may activate a profile, and compose activates
 * {@code json-logs}, so the other two cases read the source instead: every document of the shipped file,
 * whatever profile it is guarded by, and the absence of any other file Boot would load beside it.
 */
class ShippedFlywayConfigurationTest {

    /** Boot's default migration location, the only one the shipped configuration may name. */
    private static final String MIGRATIONS = "classpath:db/migration";

    /** The jar's resources, relative to the repository root. */
    private static final String JAR_RESOURCES = "backend/src/main/resources";

    /** The shipped configuration, relative to {@link #JAR_RESOURCES}. */
    private static final String SHIPPED_FILE = "application.yml";

    /** The callback-location setting, in the form {@link #canonical} gives every relaxed spelling of it. */
    private static final String CALLBACK_LOCATIONS = "spring.flyway.callbacklocations";

    /** The migration-location setting, in the same form. */
    private static final String LOCATIONS = "spring.flyway.locations";

    /** A list element's index, which Boot binds as one entry of the list it follows. */
    private static final Pattern LIST_INDEX = Pattern.compile("\\[\\d+]$");

    /**
     * A configuration file Boot would load from the jar beside the shipped one: a profile's file, in any
     * format Boot reads, or a base file in another format, which no case here would read.
     */
    private static final Pattern OTHER_CONFIGURATION_FILE =
            Pattern.compile("application(-.+\\.(yml|yaml|properties)|\\.yaml|\\.properties)");

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(BindsFlywayProperties.class);

    @Test
    void GivenTheShippedConfiguration_WhenFlywayIsConfigured_ThenItMigratesFromTheMigrationDirectoryAlone() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FlywayProperties.class).getLocations()).containsExactly(MIGRATIONS);
        });
    }

    @Test
    void GivenTheShippedConfiguration_WhenFlywayIsConfigured_ThenItNamesNoCallbackLocation() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FlywayProperties.class).getCallbackLocations())
                    .as("a callback location in the jar's configuration runs its scripts in every deployment,"
                            + " the demo account's included")
                    .isEmpty();
        });
    }

    /**
     * A document guarded by a profile is invisible to the two cases above, which activate none, but a
     * deployment that activates the profile reads it. So no document of the shipped file, guarded or not,
     * may name a callback location or a migration location of its own.
     */
    @Test
    void GivenEveryDocumentOfTheShippedFile_WhenItsFlywaySettingsAreRead_ThenNoneNamesACallbackOrAnotherLocation() {
        List<Map<String, String>> documents = documents(readFile(resources().resolve(SHIPPED_FILE)));

        assertThat(documents)
                .as("no document parsed out of %s; finding none means this test is reading nothing", SHIPPED_FILE)
                .isNotEmpty();
        assertThat(flywayLocationViolations(documents))
                .as(
                        "every document of %s, whatever profile guards it, must name no callback location and no"
                                + " migration location but %s",
                        SHIPPED_FILE, MIGRATIONS)
                .isEmpty();
    }

    /**
     * Boot loads a profile's own file from the jar when the profile is active, and a base file in another
     * format beside {@code application.yml}. Either could name a location that no case here reads, so the
     * jar carries the one file and nothing else.
     */
    @Test
    void GivenTheJarsResources_WhenTheirConfigurationFilesAreListed_ThenThereIsNoneButTheShippedFile() {
        List<String> others;
        try (Stream<Path> files = Files.walk(resources())) {
            others = files.filter(Files::isRegularFile)
                    .map(file -> resources().relativize(file).toString())
                    .filter(name -> OTHER_CONFIGURATION_FILE
                            .matcher(Path.of(name).getFileName().toString())
                            .matches())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + resources(), e);
        }

        assertThat(others)
                .as("configuration files the jar would carry beside %s, which no case here reads", SHIPPED_FILE)
                .isEmpty();
    }

    /** The rule the per-document case applies, on fixture YAML, in each spelling Boot binds. */
    @Test
    void GivenDocumentsNamingFlywayLocations_WhenChecked_ThenEachForbiddenOneIsReportedAndTheDefaultIsNot() {
        assertThat(flywayLocationViolations(documents("""
                        spring:
                          flyway:
                            locations: classpath:db/migration
                        """))).isEmpty();
        assertThat(flywayLocationViolations(documents("""
                        spring:
                          flyway:
                            enabled: true
                        ---
                        spring:
                          config:
                            activate:
                              on-profile: json-logs
                          flyway:
                            callback-locations: classpath:db/demo
                        """))).hasSize(1);
        assertThat(flywayLocationViolations(documents("spring.flyway.callbackLocations: filesystem:/seed")))
                .hasSize(1);
        assertThat(flywayLocationViolations(documents("""
                        spring:
                          flyway:
                            callback_locations:
                              - classpath:db/demo
                        """))).hasSize(1);
        assertThat(flywayLocationViolations(documents("""
                        spring:
                          flyway:
                            locations: classpath:db
                        """))).hasSize(1);
        assertThat(flywayLocationViolations(documents("""
                        spring:
                          flyway:
                            locations:
                              - classpath:db/migration
                              - classpath:db/demo
                        """))).hasSize(1);
    }

    /**
     * Every Flyway location the documents name that the jar may not: any callback location, and any migration
     * location but {@link #MIGRATIONS}, each as {@code key = value}.
     */
    private static List<String> flywayLocationViolations(List<Map<String, String>> documents) {
        List<String> violations = new ArrayList<>();
        for (Map<String, String> document : documents) {
            document.forEach((key, value) -> {
                String setting = LIST_INDEX.matcher(canonical(key)).replaceAll("");
                boolean forbidden = setting.equals(CALLBACK_LOCATIONS)
                        || (setting.equals(LOCATIONS) && !value.strip().equals(MIGRATIONS));
                if (forbidden) {
                    violations.add(key + " = " + value);
                }
            });
        }
        return violations;
    }

    /**
     * A property name with its relaxed spellings folded together, as Boot binds them:
     * {@code callback-locations}, {@code callbackLocations} and {@code callback_locations} are one setting.
     */
    private static String canonical(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Every document of a YAML text, each flattened to Boot's property names: nested keys joined with
     * {@code .}, list elements indexed as {@code [n]}. The safe constructor builds only maps, lists and
     * scalars, so nothing in the text can make the parser instantiate a class.
     */
    private static List<Map<String, String>> documents(String yaml) {
        List<Map<String, String>> documents = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(yaml)) {
            Map<String, String> properties = new LinkedHashMap<>();
            flatten("", document, properties);
            documents.add(properties);
        }
        return documents;
    }

    private static void flatten(String prefix, Object node, Map<String, String> properties) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) ->
                    flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, value, properties));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), properties);
            }
        } else if (node != null) {
            properties.put(prefix, String.valueOf(node));
        }
    }

    private static Path resources() {
        return RepositoryVersion.repositoryRoot().resolve(JAR_RESOURCES);
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlywayProperties.class)
    static class BindsFlywayProperties {}
}
