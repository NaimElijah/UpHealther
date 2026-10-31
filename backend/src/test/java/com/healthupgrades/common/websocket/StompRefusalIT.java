package com.healthupgrades.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.support.PostgresIT;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-32 over a real socket: each STOMP frame the server refuses is answered with an ERROR frame whose
 * {@code message} is one fixed word for its kind, never the exception text or the name of a channel
 * inside the server.
 *
 * <p>Only the assembled application can show this. Spring wraps the interceptor's exception on the way
 * out of the inbound channel, and the words the client reads are whatever the registered error handler
 * makes of that wrapper. The words are literals here, not the production constants, because they are
 * the wire contract a client codes against.
 *
 * <p>Nothing sleeps: each socket completes a future when its ERROR frame arrives, and a test waits on it
 * with a bound. The annotations match {@code CorrelationIT}'s so the classes share one context.
 */
@AutoConfigureTracing
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompRefusalIT extends PostgresIT {

    /** The ERROR frame's words for a CONNECT that presented no usable token. */
    private static final String UNAUTHENTICATED = "Unauthenticated";

    /** The ERROR frame's words for a SUBSCRIBE or SEND the server does not accept. */
    private static final String REFUSED = "Refused";

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** Long enough for a loaded CI runner; a refused frame is answered in milliseconds. */
    private static final long WAIT_SECONDS = 10;

    private static final HttpClient http = HttpClient.newHttpClient();

    private final ObjectMapper json = new ObjectMapper();

    private final WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());

    @LocalServerPort
    private int port;

    @AfterAll
    static void closeClient() {
        http.close();
    }

    @Test
    void GivenAConnectWithNoToken_WhenTheServerRefusesIt_ThenTheErrorFrameSaysUnauthenticated() throws Exception {
        Socket socket = open(new StompHeaders());

        assertThat(errorMessage(socket)).isEqualTo(UNAUTHENTICATED);
    }

    @Test
    void GivenAConnectWithAnUnusableToken_WhenTheServerRefusesIt_ThenTheErrorFrameSaysUnauthenticated()
            throws Exception {
        Socket socket = open(bearer("not-a-token"));

        assertThat(errorMessage(socket)).isEqualTo(UNAUTHENTICATED);
    }

    @Test
    void GivenAConnectedSocket_WhenItSubscribesToADestinationNothingPublishesTo_ThenTheErrorFrameSaysRefused()
            throws Exception {
        Socket socket = open(bearer(registeredAccessToken()));

        socket.session
                .get(WAIT_SECONDS, TimeUnit.SECONDS)
                .subscribe("/topic/everyone", new StompSessionHandlerAdapter() {});

        assertThat(errorMessage(socket)).isEqualTo(REFUSED);
    }

    @Test
    void GivenAConnectedSocket_WhenItSendsAFrame_ThenTheErrorFrameSaysRefused() throws Exception {
        Socket socket = open(bearer(registeredAccessToken()));

        socket.session.get(WAIT_SECONDS, TimeUnit.SECONDS).send("/app/anything", new byte[0]);

        assertThat(errorMessage(socket)).isEqualTo(REFUSED);
    }

    // ------------------------------------------------------------------ the socket

    /** One client socket: the session, once the server accepts the CONNECT, and the ERROR frame it sent. */
    private record Socket(CompletableFuture<StompSession> session, CompletableFuture<StompHeaders> error) {}

    /**
     * Opens a socket and sends the CONNECT. The session future is the client's own, so it is left alone
     * when the server refuses the CONNECT; the ERROR frame is what such a test waits on.
     */
    private Socket open(StompHeaders connectHeaders) {
        CompletableFuture<StompHeaders> error = new CompletableFuture<>();
        CompletableFuture<StompSession> session = stomp.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        // Only ERROR reaches the session handler: a subscription's frames go to its own.
                        error.complete(headers);
                    }
                });
        return new Socket(session, error);
    }

    private static String errorMessage(Socket socket) throws Exception {
        return socket.error.get(WAIT_SECONDS, TimeUnit.SECONDS).getFirst("message");
    }

    private static StompHeaders bearer(String token) {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return headers;
    }

    // ------------------------------------------------------------------ the wire

    private String registeredAccessToken() throws Exception {
        // Unique per run: the container is shared by every IT in the build.
        String email = "stomp-refusal-" + UUID.randomUUID() + "@example.com";
        String payload = json.writeValueAsString(Map.of("name", "Someone", "email", email, "password", PASSWORD));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("accessToken").asString();
    }
}
