package com.healthupgrades;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>{@code @AutoConfigureObservability} is required, not decorative: {@code @SpringBootTest} sets
 * {@code management.tracing.enabled=false} by default, which leaves the context in a state production
 * never sees — a tracer that still mints ids, but a no-op propagator that extracts nothing. Without the
 * annotation this class would report a healthy {@code X-Trace-Id} while silently proving nothing about
 * inbound propagation.
 *
 * <p>Every endpoint used here is {@code permitAll}, so nothing depends on a seeded account or a token.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIT {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACE_ID_PATTERN = "[0-9a-f]{32}";

    private final HttpClient client = HttpClient.newHttpClient();
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
    void GivenARequestFails_WhenTheErrorBodyIsRead_ThenItsTraceIdMatchesTheHeader() throws Exception {
        // The body is what a user screenshots; the header is what a proxy log keeps. They have to agree,
        // or the id means two different things depending on who reports the failure.
        HttpResponse<String> response = post("not json", null);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("traceId").asText())
                .matches(TRACE_ID_PATTERN)
                .isEqualTo(traceIdHeader(response));
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

    private static String traceIdHeader(HttpResponse<String> response) {
        return response.headers().firstValue(TRACE_ID_HEADER).orElse(null);
    }
}
