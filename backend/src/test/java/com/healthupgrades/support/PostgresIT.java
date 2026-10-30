package com.healthupgrades.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every {@code *IT}: supplies the PostgreSQL the integration tests run against.
 *
 * <p>The container is started by this class rather than supplied through {@code DB_*} environment
 * variables, because a supplied database can be the <em>wrong</em> database. A local PostgreSQL service
 * already listening on 5432 is accepted silently, and the suite then migrates and validates a schema
 * nobody meant to test — passing or failing for reasons that have nothing to do with the change under
 * test. See {@code docs/ADRs/ADR-008-testcontainers-for-the-integration-test-database.md}.
 *
 * <p>The image matches {@code docker-compose.yml} deliberately: the schema is owned by Flyway migrations
 * written for PostgreSQL and Hibernate boots with {@code ddl-auto: validate}, so testing against a
 * different engine — or a different major version — would validate against a dialect production never
 * sees. The tag lives in {@link PostgresImage}, and {@code ContainerImagesArePinnedTest} fails the build
 * if compose names a different one.
 *
 * <p><strong>Singleton, not {@code @Container}.</strong> The field is started once in a static
 * initialiser and deliberately left unmanaged by the Testcontainers JUnit extension, so all the
 * integration tests in a build share one database instead of paying a container start per class.
 * Testcontainers' own reaper removes it when the JVM exits.
 *
 * <p>{@code @ServiceConnection} is what points Spring's {@code DataSource} at it; no test needs to read
 * a URL, and {@code application.yml}'s {@code DB_*} defaults are never consulted during {@code verify}.
 *
 * <p><strong>The sign-in rate limit is raised here for every integration test.</strong> The real limit
 * is ten attempts a minute per address, and an IT suite registers and signs in far more often than
 * that from one address — the loopback — so the limit would start refusing tests partway through a
 * run, in an order that depends on how fast the machine is. Raised on this base class rather than on
 * each subclass so every {@code *IT} keeps the same context cache key and the suite still starts one
 * application. {@code RateLimitedSignInTest} is where the limit itself is asserted.
 *
 * <p><strong>A cached context keeps one idle connection, not ten.</strong> Spring keeps every distinct
 * test context alive for the whole run, and each holds its own Hikari pool, whose minimum idle defaults
 * to its maximum of ten. Every {@code @DataJpaTest} that imports a different adapter is a distinct
 * context, so idle pools alone outgrew PostgreSQL's hundred connections once one more persistence IT
 * was added: whichever context started last was refused with "too many clients" and its whole class
 * errored before an assertion ran. A minimum idle of one bounds what a context holds once its tests are
 * done, while its pool can still grow to ten for a test that needs them concurrently. Set here, for the
 * same reason as the rate limit: one value for every {@code *IT}, one context cache key.
 */
@TestPropertySource(properties = {"app.rate-limit.limit=1000000", "spring.datasource.hikari.minimum-idle=1"})
public abstract class PostgresIT {

    /** The image {@code docker-compose.yml} runs too, so tests and production share a dialect. */
    private static final DockerImageName IMAGE = DockerImageName.parse(PostgresImage.NAME);

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE);

    /** What a database of its own may be called: {@code CREATE DATABASE} takes no bind parameter. */
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    static {
        POSTGRES.start();
    }

    /**
     * Creates an empty database of its own in the shared container, for a test that migrates a schema one
     * version at a time.
     *
     * <p>Every other {@code *IT} shares the container's default database, which Flyway has already taken to
     * the latest version, so a test of what one migration does to the rows written before it needs a
     * database nobody else migrates. An earlier one of the same name is dropped first, so a test can run
     * twice in one JVM.
     *
     * @param name the database's name, lowercase letters, digits and underscores
     * @return where it is, and how to sign in to it
     * @throws IllegalArgumentException if the name is not one {@code CREATE DATABASE} can take as it is
     * @throws SQLException if the container refuses either statement
     */
    protected static IsolatedDatabase createIsolatedDatabase(String name) throws SQLException {
        if (!DATABASE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a plain database name: " + name);
        }
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name);
            statement.execute("CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        return new IsolatedDatabase(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * A database created by {@link #createIsolatedDatabase}.
     *
     * @param jdbcUrl  its JDBC URL
     * @param username the role that owns it
     * @param password that role's password
     */
    protected record IsolatedDatabase(String jdbcUrl, String username, String password) {

        /**
         * Opens a connection to it, which the caller closes.
         *
         * @throws SQLException if the container refuses the connection
         */
        public Connection connect() throws SQLException {
            return DriverManager.getConnection(jdbcUrl, username, password);
        }
    }
}
