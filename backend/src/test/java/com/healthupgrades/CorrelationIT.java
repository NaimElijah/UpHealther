package com.healthupgrades;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.PostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks the correlation contract over a real socket: the header a support engineer would ask for, and
 * the id in the body a user would screenshot.
 *
 * <p>The division of labour with {@code RequestCorrelationTest} is deliberate. That one owns the MDC
 * lifecycle, because pinning "the same thread" needs a thread this test cannot pin — with a real Tomcat
 * the request lands wherever the connector puts it. This one owns the wire contract, which a unit test
 * cannot see: filter ordering, the security chain, and Jackson's view of the body.
 *
 * <p>It deliberately uses the JDK's {@link HttpClient} rather than {@code TestRestTemplate}. Boot
 * instruments its own HTTP clients, so a {@code RestTemplate} from the context opens a client-side span
 * and <b>overwrites</b> any {@code traceparent} the test sets — which turns the inbound-propagation
 * assertion into a test of Spring's client instrumentation rather than of this application. An
 * uninstrumented client is the only way to send a header the server actually receives.
 *
 * <p>{@code @AutoConfigureTracing} is required, not decorative. {@code @SpringBootTest} otherwise switches
 * tracing down to something production never runs: Boot 4 turns its export off
 * ({@code management.tracing.export.enabled=false}), and Boot 3 turned tracing off altogether, leaving a
 * tracer that still minted ids but a no-op propagator that extracted nothing. In that state this class
 * would report a healthy {@code X-Trace-Id} while proving nothing about inbound propagation. The
 * annotation rules it out whatever a given version switches off. {@code @AutoConfigureMetrics} is here
 * only so that this class and {@code ActuatorEndpointsIT} declare the same context and share one.
 *
 * <p>Every endpoint used here is {@code permitAll}, so nothing depends on a seeded account or a token.
 */
@AutoConfigureTracing
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIT extends PostgresIT {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    /** A trace id sent in B3 form, distinct from the W3C one so neither test can pass on the other's. */
    private static final String INBOUND_B3_TRACE_ID = "5c0a9e3d1b7f4e26a8d4c1f09b3e7a52";

    private static final String TRACE_ID_PATTERN = "[0-9a-f]{32}";

    private static final HttpClient client = HttpClient.newHttpClient();

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void GivenAnyRequest_WhenItIsAnswered_ThenTheResponseCarriesATraceIdHeader() throws Exception {
        HttpResponse<String> response = post("{}", null);

        assertThat(traceIdHeader(response))
                .as("a caller must be able to quote an id without reading the body")
                .isNotNull()
                .matches(TRACE_ID_PATTERN);
    }

    @Test
    void GivenAnInboundTraceparent_WhenTheRequestIsAnswered_ThenTheSameTraceIsContinued() throws Exception {
        HttpResponse<String> response = post("{}", "00-" + INBOUND_TRACE_ID + "-00f067aa0ba902b7-01");

        assertThat(traceIdHeader(response)).isEqualTo(INBOUND_TRACE_ID);
    }

    @Test
    void GivenAB3Header_WhenTheRequestIsAnswered_ThenItsTraceIsNotContinued() throws Exception {
        // ADR-067: trace context is accepted from a trusted hop only, and that hop speaks W3C. Boot's
        // default consumes B3 too, so a client could choose the id the logs and the audit trail join on
        // by sending b3 instead of the traceparent the proxy strips.
        HttpResponse<String> response = postWithHeaders("b3", INBOUND_B3_TRACE_ID + "-00f067aa0ba902b7-1");

        assertThat(traceIdHeader(response)).matches(TRACE_ID_PATTERN).isNotEqualTo(INBOUND_B3_TRACE_ID);
    }

    @Test
    void GivenMultiHeaderB3_WhenTheRequestIsAnswered_ThenItsTraceIsNotContinued() throws Exception {
        HttpResponse<String> response = postWithHeaders(
                "X-B3-TraceId", INBOUND_B3_TRACE_ID,
                "X-B3-SpanId", "00f067aa0ba902b7",
                "X-B3-Sampled", "1");

        assertThat(traceIdHeader(response)).matches(TRACE_ID_PATTERN).isNotEqualTo(INBOUND_B3_TRACE_ID);
    }

    @Test
    void GivenARequestFails_WhenTheErrorBodyIsRead_ThenItsTraceIdMatchesTheHeader() throws Exception {
        // The body is what a user screenshots; the header is what a proxy log keeps. They have to agree,
        // or the id means two different things depending on who reports the failure.
        HttpResponse<String> response = post("not json", null);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("traceId").asString()).matches(TRACE_ID_PATTERN).isEqualTo(traceIdHeader(response));
    }

    @Test
    void GivenAnUnauthenticatedRequest_WhenSecurityRejectsIt_ThenTheBodyAndHeaderCarryTheSameTraceId()
            throws Exception {
        // The reason the filter is ordered ahead of Spring Security: this rejection is decided inside the
        // security chain. The entry point hands it to GlobalExceptionHandler, so the body carries the id
        // too, and it must be the same one the header carries.
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/upgrades"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(traceIdHeader(response)).matches(TRACE_ID_PATTERN);
        assertThat(json.readTree(response.body()).path("traceId").asString()).isEqualTo(traceIdHeader(response));
    }

    @Test
    void GivenAUrlTheFirewallRejects_WhenTheContainerErrorPageAnswers_ThenItKeepsTheFirewallsStatus() throws Exception {
        // #105. A firewall rejection is answered by sendError, so it is rendered by a container ERROR
        // dispatch to /error - the one path that never reaches GlobalExceptionHandler on the request
        // dispatch. Spring Security authorises that dispatch as well, and it carries no credentials, so
        // unless it is let through, the client is told 401 - "your session lapsed" - for a URL that was
        // simply malformed, and the SPA renews a token that was fine.
        HttpResponse<String> response = firewallRejected();

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(traceIdHeader(response)).matches(TRACE_ID_PATTERN);
    }

    @Test
    void GivenAUrlTheFirewallRejects_WhenTheContainerErrorPageAnswers_ThenTheBodyCarriesTheHeadersTraceId()
            throws Exception {
        // #105. The ERROR dispatch runs after the request's observation has closed, so nothing there can
        // read the id from the tracer; the body has to carry the one the request's own dispatch saw.
        HttpResponse<String> response = firewallRejected();

        assertThat(json.readTree(response.body()).path("traceId").asString())
                .matches(TRACE_ID_PATTERN)
                .isEqualTo(traceIdHeader(response));
    }

    @Test
    void GivenAUrlTheFirewallRejects_WhenTheContainerErrorPageAnswers_ThenItsTimestampHasNoOffsetLikeEveryErrorBody()
            throws Exception {
        // api.md: a timestamp is UTC, written without an offset. Over the wire, so it is Jackson's view of
        // the value that is checked, not the value handed to it.
        HttpResponse<String> response = firewallRejected();

        assertThat(json.readTree(response.body()).path("timestamp").asString())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    }

    /**
     * A request Spring Security's {@code StrictHttpFirewall} refuses but Tomcat passes on: Tomcat strips a
     * {@code ;} path parameter from the path it matches, while the request URI the firewall reads keeps it.
     * An encoded {@code /} or {@code \} would not do: Tomcat itself refuses those before any filter runs,
     * so no trace id is ever attached.
     */
    private HttpResponse<String> firewallRejected() throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/upgrades;x=y"))
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An empty sign-in body sent with the given header names and values, in pairs. */
    private HttpResponse<String> postWithHeaders(String... namesAndValues) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        for (int i = 0; i < namesAndValues.length; i += 2) {
            request.header(namesAndValues[i], namesAndValues[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    static void closeClient() {
        // AutoCloseable since Java 21; each instance holds a selector thread for the rest of the fork.
        client.close();
    }

    private static String traceIdHeader(HttpResponse<String> response) {
        return response.headers().firstValue(TRACE_ID_HEADER).orElse(null);
    }
}
