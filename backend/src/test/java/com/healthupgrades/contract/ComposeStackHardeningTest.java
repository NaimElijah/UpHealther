package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.RepositoryVersion;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Holds the developer stack in {@code docker-compose.yml} to the hardening scan 02 gave it (#336,
 * findings I2.1, I2.7 and I2.8): ports published on loopback only, the database probed over TCP, no
 * privilege gain in any service, and no capability left to the backend. Scan 05 added one more (#339,
 * finding S5.C7): the backend connects as a role of its own, never as the cluster's superuser, and the script
 * in {@code scripts/postgres-init/} creates that role with no administrative attribute.
 *
 * <p>Each one is a single line in the compose file whose loss changes nothing a developer sees: the stack
 * still starts and the tests still pass, while the database and the signing key are open to the network,
 * or the backend starts against a database that is not yet listening. Why each line is there is in the
 * compose file's own comments. It reads the file from the root {@link RepositoryVersion#repositoryRoot()}
 * finds, so it runs the same from Maven and from an IDE.
 */
class ComposeStackHardeningTest {

    /** The developer stack, relative to the repository root. */
    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** The prefix of a published port that only the host itself can reach. */
    private static final String LOOPBACK_BINDING = "127.0.0.1:";

    /** The {@code security_opt} entry that forbids gaining privileges through setuid or file capabilities. */
    private static final String NO_NEW_PRIVILEGES = "no-new-privileges:true";

    /** The {@code cap_drop} entry that drops every capability. */
    private static final String ALL_CAPABILITIES = "ALL";

    /** {@code pg_isready} asked over TCP on the loopback address, rather than over the Unix socket. */
    private static final Pattern PG_ISREADY_OVER_TCP =
            Pattern.compile("\\bpg_isready\\b.*\\s-h\\s+127\\.0\\.0\\.1(\\s|$)");

    /** Where the postgres image finds the scripts it runs once, when it initialises an empty volume. */
    private static final String INIT_SCRIPTS = "/docker-entrypoint-initdb.d";

    /** The script that creates the role the backend connects as, relative to the repository root. */
    private static final String APP_ROLE_SCRIPT = "scripts/postgres-init/01-create-app-role.sh";

    /** The role attributes that make a role more than a login, each of which the application role refuses. */
    private static final List<String> ADMINISTRATIVE_ATTRIBUTES =
            List.of("SUPERUSER", "CREATEDB", "CREATEROLE", "REPLICATION", "BYPASSRLS");

    /** The statement that creates the application role, up to its terminating semicolon. */
    private static final Pattern CREATE_ROLE = Pattern.compile("(?is)\\bCREATE\\s+ROLE\\b[^;]*;");

    /** A compose interpolation, {@code ${NAME}} or {@code ${NAME:-default}}, as the whole value. */
    private static final Pattern INTERPOLATION = Pattern.compile("^\\$\\{(\\w+)(?::-([^}]*))?}$");

    @Test
    void GivenTheComposeStack_WhenReadingEveryPublishedPort_ThenEachIsBoundToLoopback() {
        List<String> published = new ArrayList<>();
        List<String> unbound = new ArrayList<>();
        services().forEach((name, service) -> {
            for (String port : strings(service, "ports")) {
                published.add(port);
                if (!port.startsWith(LOOPBACK_BINDING)) {
                    unbound.add(name + ": " + port);
                }
            }
        });

        assertThat(published)
                .describedAs(
                        "no published port found in %s; finding none means this test is reading nothing", COMPOSE_FILE)
                .isNotEmpty();
        assertThat(unbound)
                .describedAs(
                        "ports published beyond the loopback address; prefix each with %s (I2.1)", LOOPBACK_BINDING)
                .isEmpty();
    }

    @Test
    void GivenTheComposeDatabase_WhenReadingItsHealthCheck_ThenItProbesOverTcp() {
        Map<?, ?> healthcheck = mapping(service("postgres").get("healthcheck"), "services.postgres.healthcheck");
        Object test = healthcheck.get("test");
        String command = test instanceof List<?> parts
                ? String.join(" ", parts.stream().map(String::valueOf).toList())
                : String.valueOf(test);

        assertThat(command)
                .describedAs("the postgres health-check must run pg_isready with -h 127.0.0.1; over the Unix "
                        + "socket the image's temporary init server answers first (I2.7)")
                .containsPattern(PG_ISREADY_OVER_TCP);
    }

    @Test
    void GivenEveryComposeService_WhenReadingItsSecurityOptions_ThenEachForbidsPrivilegeGain() {
        Map<String, Map<?, ?>> services = services();

        List<String> permitting = services.entrySet().stream()
                .filter(service -> !strings(service.getValue(), "security_opt").contains(NO_NEW_PRIVILEGES))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(permitting)
                .describedAs("services whose security_opt lacks %s (I2.8)", NO_NEW_PRIVILEGES)
                .isEmpty();
    }

    @Test
    void GivenTheComposeBackend_WhenReadingTheCapabilitiesItDrops_ThenItDropsAll() {
        assertThat(strings(service("backend"), "cap_drop"))
                .describedAs("the backend's cap_drop must contain %s (I2.8)", ALL_CAPABILITIES)
                .contains(ALL_CAPABILITIES);
    }

    @Test
    void GivenTheComposeBackend_WhenReadingTheRoleItConnectsAs_ThenItIsTheApplicationRoleNotTheSuperuser() {
        // The image makes POSTGRES_USER the cluster's superuser, and a backend connecting as it would run
        // any SQL it could be made to run with every privilege the cluster has (S5.C7, ADR-069).
        Map<?, ?> database = environment("postgres");
        Map<?, ?> backend = environment("backend");

        assertThat(database.get("POSTGRES_APP_USER"))
                .describedAs("the postgres service must name the application role")
                .isNotNull();
        assertThat(effective(database.get("POSTGRES_APP_USER")))
                .describedAs("the application role must not be the superuser the image creates from POSTGRES_USER")
                .isNotEqualTo(effective(database.get("POSTGRES_USER")));
        assertThat(backend.get("DB_USERNAME"))
                .describedAs("the backend must connect as the application role the postgres service creates")
                .isEqualTo(database.get("POSTGRES_APP_USER"));
        assertThat(backend.get("DB_PASSWORD"))
                .describedAs("the backend must sign in with the application role's password")
                .isEqualTo(database.get("POSTGRES_APP_PASSWORD"));
        assertThat(strings(service("postgres"), "volumes"))
                .describedAs(
                        "the postgres service must mount scripts/postgres-init/, which creates that role, at %s",
                        INIT_SCRIPTS)
                .anyMatch(volume -> volume.startsWith("./scripts/postgres-init:" + INIT_SCRIPTS));
    }

    @Test
    void GivenTheApplicationRoleScript_WhenReadingTheRoleItCreates_ThenItRefusesEveryAdministrativeAttribute() {
        // The images check proves the backend can sign in and migrate as this role, which a superuser
        // could do just as well; only the script says what the role may not do (S5.C7, ADR-069).
        String sql = withoutShellComments(read(APP_ROLE_SCRIPT));
        var createRole = CREATE_ROLE.matcher(sql);
        assertThat(createRole.find())
                .describedAs("%s must create the application role with CREATE ROLE", APP_ROLE_SCRIPT)
                .isTrue();

        for (String attribute : ADMINISTRATIVE_ATTRIBUTES) {
            assertThat(createRole.group())
                    .describedAs("the application role must be created NO%s", attribute)
                    .containsPattern("(?i)\\bNO" + attribute + "\\b");
            assertThat(sql)
                    .describedAs("%s must not grant %s to any role", APP_ROLE_SCRIPT, attribute)
                    .doesNotContainPattern("(?i)\\b" + attribute + "\\b");
        }
        assertThat(sql)
                .describedAs(
                        "%s must grant nothing: the role owns its database and holds no other privilege",
                        APP_ROLE_SCRIPT)
                .doesNotContainPattern("(?i)\\bGRANT\\b");
    }

    /**
     * The value compose gives a variable when the environment does not set it: the default of a
     * {@code ${NAME:-default}}, the name itself of a bare {@code ${NAME}}, or a literal as written.
     */
    private static String effective(Object value) {
        String text = String.valueOf(value);
        var interpolation = INTERPOLATION.matcher(text);
        if (!interpolation.matches()) {
            return text;
        }
        return interpolation.group(2) != null ? interpolation.group(2) : "${" + interpolation.group(1) + "}";
    }

    /** The script with every shell comment line removed, so a word in a comment is not read as SQL. */
    private static String withoutShellComments(String script) {
        return String.join(
                "\n",
                script.lines()
                        .filter(line -> !line.stripLeading().startsWith("#"))
                        .toList());
    }

    /** A file under the repository root, as text. */
    private static String read(String relativePath) {
        Path file = RepositoryVersion.repositoryRoot().resolve(relativePath);
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** One service's {@code environment}, as a mapping of each variable to its value. */
    private static Map<?, ?> environment(String name) {
        return mapping(service(name).get("environment"), "services." + name + ".environment");
    }

    /** One service's definition, failing if the compose file has no such service. */
    private static Map<?, ?> service(String name) {
        Map<String, Map<?, ?>> services = services();
        assertThat(services)
                .describedAs("%s defines no service %s", COMPOSE_FILE, name)
                .containsKey(name);
        return services.get(name);
    }

    /** Every service's definition by name, in file order; fails when there are none. */
    private static Map<String, Map<?, ?>> services() {
        Map<?, ?> document = mapping(load(), COMPOSE_FILE);
        Map<String, Map<?, ?>> services = new LinkedHashMap<>();
        mapping(document.get("services"), "services")
                .forEach((name, body) -> services.put(String.valueOf(name), mapping(body, "services." + name)));
        assertThat(services)
                .describedAs("%s defines no services; this test would be reading nothing", COMPOSE_FILE)
                .isNotEmpty();
        return services;
    }

    /** A sequence under a service's key, each entry as a string; empty when the key is absent. */
    private static List<String> strings(Map<?, ?> service, String key) {
        Object node = service.get(key);
        if (node == null) {
            return List.of();
        }
        assertThat(node).describedAs("%s is not a sequence", key).isInstanceOf(List.class);
        return ((List<?>) node).stream().map(String::valueOf).toList();
    }

    private static Map<?, ?> mapping(Object node, String path) {
        assertThat(node)
                .describedAs("%s in %s is not a mapping", path, COMPOSE_FILE)
                .isInstanceOf(Map.class);
        return (Map<?, ?>) node;
    }

    /**
     * The parsed compose file. The safe constructor builds only maps, lists and scalars, so nothing in the
     * file can make the parser instantiate a class.
     */
    private static Object load() {
        Path file = RepositoryVersion.repositoryRoot().resolve(COMPOSE_FILE);
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }
}
