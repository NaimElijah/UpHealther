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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-32 over a real socket: each STOMP frame the server refuses is answered with an ERROR frame whose
 * {@code message} is one fixed word for its kind, never the exception text or the name of a channel
 * inside the server, and the socket is then closed (ADR-078).
 *
 * <p>Only the assembled application can show this. Spring wraps the interceptor's exception on the way
 * out of the inbound channel, and the words the client reads are whatever the registered error handler
 * makes of that wrapper. The words are literals here, not the production constants, because they are
 * the wire contract a client codes against.
 *
 * <p>Two frames cannot come from Spring's STOMP client: one sent before any CONNECT, because the client
 * always connects first, and a MESSAGE, which it never sends. Those cases write their frames to a bare
 * WebSocket.
 *
 * <p>Nothing sleeps: each socket completes one future when its ERROR frame arrives and another when its
 * connection drops, and a test waits on them with a bound. The annotations match {@code CorrelationIT}'s
 * so the classes share one context.
 */
@AutoConfigureTracing
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompRefusalIT extends PostgresIT {

    /** The ERROR frame's words for a CONNECT that presented no usable token. */
    private static final String UNAUTHENTICATED = "Unauthenticated";

    /** The ERROR frame's words for any frame on a connected socket that the interceptor refuses. */
    private static final String REFUSED = "Refused";

    /** The ERROR frame's words for any other frame the server did not process. */
    private static final String NOT_PROCESSED = "Frame not processed";

    /** A STOMP SEND with no CONNECT before it, as it goes on the wire: command, header, blank line, NUL. */
    private static final String BARE_SEND = "SEND\ndestination:/app/anything\n\n\u0000";

    /** The header line of an ERROR frame that carries its words. */
    private static final String MESSAGE_HEADER = "message:";

    /** The first line of the frame the server answers an accepted CONNECT with. */
    private static final String CONNECTED_COMMAND = "CONNECTED\n";

    /** The first line of an ERROR frame. */
    private static final String ERROR_COMMAND = "ERROR\n";

    /** The user-queue suffix the notification push adapter delivers to. */
    private static final String NOTIFICATIONS = "/queue/notifications";

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
        assertClosed(socket);
    }

    @Test
    void GivenAConnectWithAnUnusableToken_WhenTheServerRefusesIt_ThenTheErrorFrameSaysUnauthenticated()
            throws Exception {
        Socket socket = open(bearer("not-a-token"));

        assertThat(errorMessage(socket)).isEqualTo(UNAUTHENTICATED);
        assertClosed(socket);
    }

    @Test
    void GivenAConnectedSocket_WhenItSubscribesToADestinationNothingPublishesTo_ThenTheErrorFrameSaysRefused()
            throws Exception {
        Socket socket = open(bearer(registeredAccessToken()));

        socket.session
                .get(WAIT_SECONDS, TimeUnit.SECONDS)
                .subscribe("/topic/everyone", new StompSessionHandlerAdapter() {});

        assertThat(errorMessage(socket)).isEqualTo(REFUSED);
        assertClosed(socket);
    }

    @Test
    void GivenAConnectedSocket_WhenItSendsAFrame_ThenTheErrorFrameSaysRefused() throws Exception {
        Socket socket = open(bearer(registeredAccessToken()));

        socket.session.get(WAIT_SECONDS, TimeUnit.SECONDS).send("/app/anything", new byte[0]);

        assertThat(errorMessage(socket)).isEqualTo(REFUSED);
        assertClosed(socket);
    }

    @Test
    void GivenAConnectedSocket_WhenItSendsAMessageFrame_ThenTheErrorFrameSaysRefused() throws Exception {
        // Finding D1.1. MESSAGE is the frame the server delivers a subscription in, and Spring routes one
        // from a client exactly as it would a SEND: passed, one addressed to another account's user queue
        // reached that account's socket as a notification. Spring's STOMP client cannot send a MESSAGE,
        // so this writes the frames to a bare WebSocket.
        BareSocket socket = openBare();
        socket.session.sendMessage(new TextMessage(connectFrame(registeredAccessToken())));
        socket.connected.get(WAIT_SECONDS, TimeUnit.SECONDS);

        socket.session.sendMessage(new TextMessage(messageFrameTo("/user/" + UUID.randomUUID() + NOTIFICATIONS)));

        assertThat(messageHeaderOf(socket.error.get(WAIT_SECONDS, TimeUnit.SECONDS)))
                .containsExactly(MESSAGE_HEADER + REFUSED);
        assertClosedAsAProtocolError(socket);
    }

    @Test
    void GivenASocketThatNeverConnected_WhenItSendsAFrame_ThenTheErrorFrameSaysFrameNotProcessed() throws Exception {
        // Spring refuses a frame on a session it has no CONNECT for ("Unknown session") before the
        // interceptor runs, so this is a frame not processed, not one the interceptor refused.
        BareSocket socket = openBare();

        socket.session.sendMessage(new TextMessage(BARE_SEND));

        assertThat(messageHeaderOf(socket.error.get(WAIT_SECONDS, TimeUnit.SECONDS)))
                .containsExactly(MESSAGE_HEADER + NOT_PROCESSED);
        assertClosedAsAProtocolError(socket);
    }

    // ------------------------------------------------------------------ the socket

    /**
     * One client socket: the session, once the server accepts the CONNECT; the ERROR frame it sent; and
     * the failure the client reports when the server then closes the connection.
     */
    private record Socket(
            CompletableFuture<StompSession> session,
            CompletableFuture<StompHeaders> error,
            CompletableFuture<Throwable> closed) {}

    /**
     * Opens a socket and sends the CONNECT. The session future is the client's own, so it is left alone
     * when the server refuses the CONNECT; the ERROR frame is what such a test waits on.
     */
    private Socket open(StompHeaders connectHeaders) {
        CompletableFuture<StompHeaders> error = new CompletableFuture<>();
        CompletableFuture<Throwable> closed = new CompletableFuture<>();
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

                    @Override
                    public void handleTransportError(StompSession stompSession, Throwable exception) {
                        closed.complete(exception);
                    }
                });
        return new Socket(session, error, closed);
    }

    private static String errorMessage(Socket socket) throws Exception {
        return socket.error.get(WAIT_SECONDS, TimeUnit.SECONDS).getFirst("message");
    }

    /** The server closes the socket after every ERROR frame, which the client reports as a lost connection. */
    private static void assertClosed(Socket socket) throws Exception {
        assertThat(socket.closed.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(ConnectionLostException.class);
    }

    /**
     * A WebSocket with no STOMP client on it, for the frames Spring's client will not send: one before
     * any CONNECT, and a MESSAGE. Its futures complete with what the server sends back: the CONNECTED
     * frame it answers a CONNECT with, the first ERROR frame, and the status it closes the socket with.
     */
    private record BareSocket(
            WebSocketSession session,
            CompletableFuture<String> connected,
            CompletableFuture<String> error,
            CompletableFuture<CloseStatus> closed) {}

    private BareSocket openBare() throws Exception {
        CompletableFuture<String> connected = new CompletableFuture<>();
        CompletableFuture<String> error = new CompletableFuture<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(WebSocketSession webSocket, TextMessage frame) {
                                String payload = frame.getPayload();
                                if (payload.startsWith(CONNECTED_COMMAND)) {
                                    connected.complete(payload);
                                } else if (payload.startsWith(ERROR_COMMAND)) {
                                    error.complete(payload);
                                }
                            }

                            @Override
                            public void afterConnectionClosed(WebSocketSession webSocket, CloseStatus status) {
                                closed.complete(status);
                            }
                        },
                        "ws://localhost:{port}/ws",
                        port)
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        return new BareSocket(session, connected, error, closed);
    }

    /**
     * Spring closes the socket after every ERROR frame it sends, with the protocol-error status. The code
     * alone is compared: the reason phrase is the client library's to fill in.
     */
    private static void assertClosedAsAProtocolError(BareSocket socket) throws Exception {
        assertThat(socket.closed.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .extracting(CloseStatus::getCode)
                .isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
    }

    /** A STOMP 1.2 CONNECT carrying the token, with heartbeats off so nothing else crosses the socket. */
    private static String connectFrame(String token) {
        return "CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n\n\u0000";
    }

    /** The frame a server delivers a subscription's messages in, written by a client instead. */
    private static String messageFrameTo(String destination) {
        return "MESSAGE\ndestination:" + destination + "\ncontent-type:text/plain\n\nspoofed\u0000";
    }

    private static Stream<String> messageHeaderOf(String frame) {
        return frame.lines().filter(header -> header.startsWith(MESSAGE_HEADER));
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
