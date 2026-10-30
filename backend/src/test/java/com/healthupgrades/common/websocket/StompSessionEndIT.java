package com.healthupgrades.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.auth.adapter.in.web.RefreshCookies;
import com.healthupgrades.auth.application.port.in.SessionCommand;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-35 and FR-43 over a real socket: a session that ends takes the STOMP sockets it opened with it, at
 * once, rather than leaving them to receive the account's notifications until they next reconnect (#83).
 *
 * <p>Only the assembled application can show this. It needs the principal the CONNECT frame attached to
 * reach Spring's connect event, the session's end to be announced only once it has committed, and the
 * ERROR frame sent on the outbound channel to actually close the WebSocket. Which sockets a given end
 * selects is {@code StompSessionRegistryTest}'s subject; this class proves the wiring.
 *
 * <p>Nothing sleeps. Each socket completes a future when its ERROR frame arrives and another when its
 * connection drops, and a test waits on those with a bound. The annotations match {@code CorrelationIT}'s
 * so the classes share one context.
 */
@AutoConfigureTracing
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompSessionEndIT extends PostgresIT {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** Long enough for a loaded CI runner; a socket that is going to close does so in milliseconds. */
    private static final long WAIT_SECONDS = 10;

    private static final HttpClient http = HttpClient.newHttpClient();

    private final ObjectMapper json = new ObjectMapper();

    private final WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());

    @LocalServerPort
    private int port;

    @Autowired
    private SessionCommand sessions;

    @AfterAll
    static void closeClient() {
        http.close();
    }

    @Test
    void GivenAnOpenSocket_WhenItsSessionIsSignedOutOf_ThenTheServerEndsTheSocketAndSaysWhy() throws Exception {
        SignedIn registered = register("socket-" + UUID.randomUUID() + "@example.com");
        Socket socket = connect(registered.accessToken());

        logout(registered.credential());

        assertThat(socket.error.get(WAIT_SECONDS, TimeUnit.SECONDS).getFirst("message"))
                .isEqualTo(StompSessionRegistry.SESSION_ENDED);
        assertThat(socket.closed.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ConnectionLostException.class);
    }

    @Test
    void GivenAnAccountWithSocketsInTwoSessions_WhenAllItsSessionsEnd_ThenEveryOneOfItsSocketsIsClosed()
            throws Exception {
        // What switching an account off does, through the port the admin context calls. Driving it over
        // HTTP instead would need an administrator, and the role is not what is being shown here.
        String email = "disabled-" + UUID.randomUUID() + "@example.com";
        SignedIn registered = register(email);
        Socket laptop = connect(registered.accessToken());
        Socket phone = connect(login(email).get("accessToken").asString());

        sessions.revokeAllForUser(registered.userId());

        assertThat(laptop.closed.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ConnectionLostException.class);
        assertThat(phone.closed.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ConnectionLostException.class);
    }

    // ------------------------------------------------------------------ the socket

    /** One client socket, and what the server did to it. */
    private record Socket(CompletableFuture<StompHeaders> error, CompletableFuture<Throwable> closed) {}

    private Socket connect(String accessToken) throws Exception {
        Socket socket = new Socket(new CompletableFuture<>(), new CompletableFuture<>());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);
        stomp.connectAsync(
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
                                // Only ERROR reaches the session handler: nothing here subscribes.
                                socket.error.complete(headers);
                            }

                            @Override
                            public void handleTransportError(StompSession session, Throwable exception) {
                                socket.closed.complete(exception);
                            }
                        })
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        return socket;
    }

    // ------------------------------------------------------------------ the wire

    /** A sign-in as a browser holds it: the access token in memory, the refresh credential in a cookie. */
    private record SignedIn(JsonNode body, String credential) {

        String accessToken() {
            return body.get("accessToken").asString();
        }

        UUID userId() {
            return UUID.fromString(body.get("user").get("id").asString());
        }
    }

    private SignedIn register(String email) throws Exception {
        String payload = json.writeValueAsString(Map.of("name", "Someone", "email", email, "password", PASSWORD));
        HttpResponse<String> response = send(request("/api/auth/register")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
        assertThat(response.statusCode()).isEqualTo(201);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return new SignedIn(
                json.readTree(response.body()), cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';')));
    }

    /** Signs the account in again, which opens a second session: another device. */
    private JsonNode login(String email) throws Exception {
        String payload = json.writeValueAsString(Map.of("email", email, "password", PASSWORD));
        HttpResponse<String> response = send(request("/api/auth/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void logout(String credential) throws Exception {
        HttpResponse<String> response = send(request("/api/auth/logout")
                .header("Cookie", "__Secure-refresh_token=" + credential)
                .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(204);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
