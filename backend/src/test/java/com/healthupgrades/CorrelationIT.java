package com.healthupgrades;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the correlation contract over a real socket: the header a support engineer would ask for, and
 * the id in the body a user would screenshot.
 *
 * <p>The division of labour with {@code RequestCorrelationTest} is deliberate. That one owns the MDC
 * lifecycle, because pinning "the same thread" needs a thread this test cannot pin — with a real Tomcat
 * the request lands wherever the connector puts it. This one owns the wire contract, which is the half
 * a unit test cannot see: filter ordering, the security chain, CORS, and Jackson's view of the body.
 *
 * <p>Every endpoint used here is {@code permitAll}, so nothing depends on a seeded account or a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIT {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void GivenAnyRequest_WhenItIsAnswered_ThenTheResponseCarriesATraceIdHeader() {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/login", json("{}"), String.class);

        assertThat(response.getHeaders().getFirst(TRACE_ID_HEADER))
                .as("a caller must be able to quote an id without reading the body")
                .isNotNull()
                .matches("[0-9a-f]{32}");
    }

    @Test
    void GivenAnInboundTraceparent_WhenTheRequestIsAnswered_ThenTheSameTraceIsContinued() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("traceparent", "00-" + INBOUND_TRACE_ID + "-00f067aa0ba902b7-01");

        ResponseEntity<String> response = rest.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);

        assertThat(response.getHeaders().getFirst(TRACE_ID_HEADER)).isEqualTo(INBOUND_TRACE_ID);
    }

    @Test
    void GivenARequestFails_WhenTheErrorBodyIsRead_ThenItsTraceIdMatchesTheHeader() {
        // The body is what a user screenshots; the header is what a proxy log keeps. They have to agree,
        // or the id identifies two different things depending on who reports the failure.
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/auth/login", HttpMethod.POST, json("not json"),
                new org.springframework.core.ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("traceId"))
                .isEqualTo(response.getHeaders().getFirst(TRACE_ID_HEADER));
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
