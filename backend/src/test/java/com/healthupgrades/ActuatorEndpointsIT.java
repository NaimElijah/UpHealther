package com.healthupgrades;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthupgrades.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the operational surface over a real socket: what answers, what it says, and — the part with
 * teeth — what does <em>not</em> answer.
 *
 * <p>`/actuator/**` is `permitAll` in `SecurityConfig`, so the only thing standing between a reader and
 * `/actuator/env` is the exposure list in `application.yml`. That list is one line and nothing else in
 * the build reads it, which makes it exactly the sort of configuration that gets widened during a
 * debugging session and never narrowed again. The negative assertions below are the guard: adding an
 * endpoint to the list is then a deliberate act that has to change a test, not an accident.
 *
 * <p>An integration test rather than a slice, because the thing being asserted is the composition —
 * Boot's endpoint auto-configuration, the exposure property, the security chain and the servlet
 * mapping. A `@WebMvcTest` would have to stub every one of those and would then be asserting its own
 * stubs.
 *
 * <p>{@code @AutoConfigureObservability} is required for the same reason it is in {@code CorrelationIT}:
 * {@code @SpringBootTest} disables metrics export by default, so without it {@code /actuator/prometheus}
 * would be absent for a reason that has nothing to do with this application's configuration.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorEndpointsIT extends PostgresIT {

    private static final HttpClient client = HttpClient.newHttpClient();

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void GivenARunningApplication_WhenHealthIsPolled_ThenItReportsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("UP");
    }

    /**
     * Liveness and readiness answer different questions, and the difference is what an orchestrator
     * acts on: a failed liveness probe means restart the process, a failed readiness probe means stop
     * sending it traffic. Restarting a process that cannot reach its database fixes nothing, which is
     * why the aggregate endpoint is the wrong thing for either to poll.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void GivenARunningApplication_WhenAProbeIsPolled_ThenItAnswersSeparatelyFromTheAggregate(String probe)
            throws Exception {
        HttpResponse<String> response = get(probe);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void GivenARunningApplication_WhenMetricsAreScraped_ThenTheFourSignalsAreThere() throws Exception {
        // One request has already been served by the probes above, so the HTTP timer exists.
        get("/actuator/health");

        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("latency and error rate")
                .contains("http_server_requests_seconds")
                .as("saturation: the JVM and the connection pool")
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections");
    }

    /**
     * Checked per metric family, not as one substring anywhere in the scrape.
     *
     * <p>The weaker form of this test passed while only {@code http_server_requests_seconds} carried
     * the tag: it is derived from an observation, and {@code management.observations.key-values} tags
     * observations rather than meters. Everything else — the JVM, the pool, and this application's own
     * counters — was unlabelled, which is precisely the set that would collide if two deployments were
     * scraped into one target. So the families here are deliberately three that are <em>not</em>
     * observation-derived, plus the HTTP one to keep it honest.
     */
    @ParameterizedTest
    @ValueSource(strings = {"http_server_requests_seconds", "jvm_memory_used_bytes",
            "hikaricp_connections", "audit_events_total"})
    void GivenAScrape_WhenAMetricFamilyIsRead_ThenEveryOneOfItsSamplesNamesThisService(String family)
            throws Exception {
        get("/actuator/health"); // so the HTTP timer has something in it
        refuseALogin(); // and so this application's own counter exists to be checked

        List<String> samples = get("/actuator/prometheus").body().lines()
                .filter(line -> line.startsWith(family) && line.contains("{"))
                .toList();

        assertThat(samples).as("%s produced no samples to check", family).isNotEmpty();
        assertThat(samples).allSatisfy(sample ->
                assertThat(sample).contains("application=\"health-upgrades-tracker\""));
    }

    /**
     * A wrong password, purely to bring {@code audit.events} into existence.
     *
     * <p>Micrometer registers a counter on its first increment, so a custom meter cannot be asserted
     * against until something has counted. The scheduled-job counters would have done as well and are
     * not used here: their crons make "has one run yet" a question of when the test happened to start.
     * A refused login needs no seeded account — the credential is rejected either way.
     */
    private void refuseALogin() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"nobody@example.com\",\"password\":\"wrong\"}"))
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The assertion this class exists for. Each of these is a real exposure — `env` prints the resolved
     * configuration including the JWT secret's property name and its neighbours, `heapdump` hands over
     * the process memory, `loggers` lets a caller turn logging off, `beans` and `mappings` describe the
     * whole application to anyone who asks. All are `permitAll` if they are exposed at all, so "not
     * exposed" is the entire control.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/heapdump", "/actuator/loggers",
            "/actuator/beans", "/actuator/mappings", "/actuator/configprops", "/actuator/threaddump"})
    void GivenAnEndpointThatWasNotExposed_WhenItIsRequested_ThenItIsNotThere(String endpoint) throws Exception {
        assertThat(get(endpoint).statusCode())
                .as("%s is readable by anyone the moment it is exposed", endpoint)
                .isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
