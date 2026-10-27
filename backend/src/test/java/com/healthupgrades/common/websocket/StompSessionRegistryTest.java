package com.healthupgrades.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.healthupgrades.common.domain.port.out.SessionStatusPort;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * NFR-35 and FR-43 for the socket: ending a session closes the sockets it opened, and switching an account
 * off closes every socket the account holds — and nothing else.
 *
 * <p>A socket is closed by sending it a STOMP ERROR frame through the client outbound channel, after
 * which Spring closes the WebSocket itself. So what is asserted here is which sockets were sent one: the
 * channel is a recording stand-in, and {@code StompSessionEndIT} shows the frame really does close a live
 * socket.
 */
class StompSessionRegistryTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private final List<Message<?>> sent = new ArrayList<>();
    private final MessageChannel channel = (message, timeout) -> sent.add(message);

    /** Sessions that have ended; every other session is live, as the auth context would answer. */
    private final Set<UUID> ended = new HashSet<>();

    private final SessionStatusPort sessionStatus = (sessionId, userId) -> !ended.contains(sessionId);

    private final StompSessionRegistry registry = new StompSessionRegistry(channel, sessionStatus);

    @Test
    void GivenTwoSocketsOfOneSessionAndOneOfAnother_WhenThatSessionEnds_ThenOnlyItsSocketsAreClosed() {
        // Sign-out is per device: the account's socket opened in its other session stays open.
        UUID signedOut = UUID.randomUUID();
        connect("tab-1", ALICE, signedOut);
        connect("tab-2", ALICE, signedOut);
        connect("phone", ALICE, UUID.randomUUID());

        registry.closeForSession(signedOut);

        assertThat(closedSockets()).containsExactlyInAnyOrder("tab-1", "tab-2");
    }

    @Test
    void GivenSocketsOfTwoAccounts_WhenOneAccountsSessionsAllEnd_ThenOnlyThatAccountsSocketsAreClosed() {
        connect("alice-laptop", ALICE, UUID.randomUUID());
        connect("alice-phone", ALICE, UUID.randomUUID());
        connect("bob-laptop", BOB, UUID.randomUUID());

        registry.closeForUser(ALICE);

        assertThat(closedSockets()).containsExactlyInAnyOrder("alice-laptop", "alice-phone");
    }

    @Test
    void GivenASocketThatHasDisconnected_WhenItsSessionEnds_ThenNothingIsSent() {
        UUID session = UUID.randomUUID();
        connect("tab", ALICE, session);
        registry.onDisconnected(new SessionDisconnectEvent(this, frameOf("tab"), "tab", CloseStatus.NORMAL, null));

        registry.closeForSession(session);

        assertThat(sent).isEmpty();
    }

    @Test
    void GivenASocketAlreadyClosedForItsSession_WhenItsAccountsSessionsAllEnd_ThenItIsNotSentAnotherFrame() {
        // A socket is forgotten as its close is sent, not only when Spring reports it gone. One that drops
        // just as it connects can be reported gone before it is recorded, and its entry would otherwise
        // outlive it for good; this bounds it to the next end that names it.
        UUID session = UUID.randomUUID();
        connect("tab", ALICE, session);
        registry.closeForSession(session);

        registry.closeForUser(ALICE);

        assertThat(closedSockets()).containsExactly("tab");
    }

    @Test
    void GivenNoSocketOpenedInASession_WhenItEnds_ThenNothingIsSent() {
        // The commonest case by far: most sign-outs come from a tab whose socket has already gone.
        connect("tab", ALICE, UUID.randomUUID());

        registry.closeForSession(UUID.randomUUID());
        registry.closeForUser(BOB);

        assertThat(sent).isEmpty();
    }

    @Test
    void GivenTheChannelRefusesOneSocketsFrame_WhenTheSessionEnds_ThenItsOtherSocketsAreStillClosed() {
        // This runs after the sign-out has committed. One socket that cannot be reached must neither fail
        // the request that signed out nor keep the session's other sockets open.
        List<String> attempted = new ArrayList<>();
        MessageChannel refusingTheFirst = (message, timeout) -> {
            attempted.add(StompHeaderAccessor.wrap(message).getSessionId());
            if (attempted.size() == 1) {
                throw new MessageDeliveryException(message, "the socket is already gone");
            }
            return sent.add(message);
        };
        StompSessionRegistry flaky = new StompSessionRegistry(refusingTheFirst, sessionStatus);
        UUID session = UUID.randomUUID();
        connect(flaky, "tab-1", ALICE, session);
        connect(flaky, "tab-2", ALICE, session);

        assertThatNoException().isThrownBy(() -> flaky.closeForSession(session));

        assertThat(attempted).containsExactlyInAnyOrder("tab-1", "tab-2");
        assertThat(closedSockets()).hasSize(1);
    }

    @Test
    void GivenTheChannelRefusedASocketsClose_WhenItsAccountsSessionsNextEnd_ThenTheCloseIsTriedAgain() {
        // A refused send says nothing about whether the socket is gone, so it is not forgotten: a socket
        // the registry lost track of could never be closed by a later end, and would stay open.
        List<String> attempted = new ArrayList<>();
        MessageChannel refusingTheFirst = (message, timeout) -> {
            attempted.add(StompHeaderAccessor.wrap(message).getSessionId());
            if (attempted.size() == 1) {
                throw new MessageDeliveryException(message, "the outbound channel is saturated");
            }
            return sent.add(message);
        };
        StompSessionRegistry flaky = new StompSessionRegistry(refusingTheFirst, sessionStatus);
        UUID session = UUID.randomUUID();
        connect(flaky, "tab", ALICE, session);
        flaky.closeForSession(session);

        flaky.closeForUser(ALICE);

        assertThat(attempted).containsExactly("tab", "tab");
        assertThat(closedSockets()).containsExactly("tab");
    }

    @Test
    void GivenTheChannelDeclinedASocketsClose_WhenItsAccountsSessionsNextEnd_ThenTheCloseIsTriedAgain() {
        // A channel may answer false rather than throw. That is a refusal too, and the socket must stay
        // recorded for the same reason as above.
        List<String> attempted = new ArrayList<>();
        MessageChannel decliningTheFirst = (message, timeout) -> {
            attempted.add(StompHeaderAccessor.wrap(message).getSessionId());
            return attempted.size() > 1 && sent.add(message);
        };
        StompSessionRegistry flaky = new StompSessionRegistry(decliningTheFirst, sessionStatus);
        UUID session = UUID.randomUUID();
        connect(flaky, "tab", ALICE, session);
        flaky.closeForSession(session);

        flaky.closeForUser(ALICE);

        assertThat(attempted).containsExactly("tab", "tab");
        assertThat(closedSockets()).containsExactly("tab");
    }

    @Test
    void GivenASessionThatEndedWhileItsSocketWasConnecting_WhenTheSocketConnects_ThenItIsClosedAtOnce() {
        // The CONNECT passed its session check, then the session ended and the sweep ran before this
        // socket was recorded, so the sweep could not see it. Asking again once it is recorded closes it.
        UUID session = UUID.randomUUID();
        ended.add(session);

        connect("tab", ALICE, session);

        assertThat(closedSockets()).containsExactly("tab");
    }

    @Test
    void GivenTheSessionCannotBeChecked_WhenASocketConnects_ThenItIsClosed() {
        // Fails closed: a socket whose session cannot be confirmed is ended, and its client reconnects
        // and is authenticated afresh. Spring would swallow the exception and leave the socket open.
        SessionStatusPort unreachable = (sessionId, userId) -> {
            throw new IllegalStateException("the session store is unreachable");
        };
        StompSessionRegistry blind = new StompSessionRegistry(channel, unreachable);

        assertThatNoException().isThrownBy(() -> connect(blind, "tab", ALICE, UUID.randomUUID()));

        assertThat(closedSockets()).containsExactly("tab");
    }

    @Test
    void GivenALiveSession_WhenItsSocketConnects_ThenItIsLeftOpen() {
        connect("tab", ALICE, UUID.randomUUID());

        assertThat(sent).isEmpty();
    }

    @Test
    void GivenAConnectionWithNoStompPrincipal_WhenItConnects_ThenThereIsNothingToCloseForIt() {
        // Every CONNECT that gets this far was authenticated, so this is a defence, not a path: a socket
        // with no principal names no session and no account, and cannot be matched to either.
        assertThatNoException()
                .isThrownBy(() -> registry.onConnected(new SessionConnectedEvent(this, frameOf("tab"), null)));

        registry.closeForUser(ALICE);

        assertThat(sent).isEmpty();
    }

    private void connect(String socket, UUID userId, UUID sessionId) {
        connect(registry, socket, userId, sessionId);
    }

    private void connect(StompSessionRegistry target, String socket, UUID userId, UUID sessionId) {
        Principal principal = new StompPrincipal(userId.toString(), sessionId);
        target.onConnected(new SessionConnectedEvent(this, frameOf(socket), principal));
    }

    /** A frame on one socket, which is all the lifecycle events carry the socket's id in. */
    private static Message<byte[]> frameOf(String socket) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK);
        accessor.setSessionId(socket);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** The sockets sent an ERROR frame saying their session ended: the ones the registry closed. */
    private List<String> closedSockets() {
        return sent.stream()
                .map(StompHeaderAccessor::wrap)
                .filter(frame -> frame.getCommand() == StompCommand.ERROR)
                .filter(frame -> StompSessionRegistry.SESSION_ENDED.equals(frame.getMessage()))
                .map(StompHeaderAccessor::getSessionId)
                .toList();
    }
}
