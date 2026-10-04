package com.healthupgrades.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
 * sees.
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

    /** Matches the image {@code docker-compose.yml} runs, so tests and production share a dialect. */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:15-alpine");

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE);

    static {
        POSTGRES.start();
    }
}
