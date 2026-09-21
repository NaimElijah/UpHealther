package com.healthupgrades.common.websocket;

import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.support.AUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The STOMP side of FR-5: a WebSocket session is authenticated by its CONNECT frame, because a browser
 * cannot put a bearer token on the handshake.
 *
 * <p>The session principal is named by the user id, which is what {@code convertAndSendToUser} routes by,
 * so the name asserted here is the one that decides whose notifications a socket receives.
 *
 * <p>Authenticating CONNECT is only half of it. A STOMP session can name any destination it likes on a
 * later frame, so the frames after CONNECT are authorised here too: a subscription must be the one
 * destination this application pushes to, and a SEND is refused outright because no {@code @MessageMapping}
 * exists for one to reach.
 */
@ExtendWith(MockitoExtension.class)
class JwtChannelInterceptorTest {

    private static final String TOKEN = "a.signed.token";

    @Mock BearerTokenAuthenticator authenticator;

    private final MessageChannel channel = mock(MessageChannel.class);
    private JwtChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new JwtChannelInterceptor(authenticator);
    }

    @Test
    void GivenAConnectWithAUsableToken_WhenItArrives_ThenTheSessionPrincipalIsTheUsersId() {
        UUID userId = UUID.randomUUID();
        when(authenticator.authenticate(TOKEN)).thenReturn(Optional.of(AUser.principalFor(userId)));

        Message<?> passed = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel);

        Principal principal = StompHeaderAccessor.wrap(passed).getUser();
        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo(userId.toString());
    }

    @Test
    void GivenAConnectWithAnUnusableToken_WhenItArrives_ThenTheConnectionIsRefused() {
        when(authenticator.authenticate(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + TOKEN), channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenAConnectWithNoToken_WhenItArrives_ThenTheConnectionIsRefusedWithoutAskingTheAuthenticator() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), channel))
                .isInstanceOf(IllegalArgumentException.class);

        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void GivenAConnectWithANonBearerCredential_WhenItArrives_ThenTheConnectionIsRefused() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Basic abc"), channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASubscribeToItsOwnUserQueue_WhenItArrives_ThenItPasses() {
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/user/queue/notifications", session());

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
    }

    @Test
    void GivenASubscribeToAnotherSessionsResolvedQueue_WhenItArrives_ThenItIsRefused() {
        // The destination the broker resolves /user/queue/notifications into. A client that names it
        // directly is asking for a queue belonging to whichever session owns that suffix, which is the
        // whole reason an allowlist of one destination is not paranoia.
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/queue/notifications-userxyz789", session());

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASubscribeToATopic_WhenItArrives_ThenItIsRefused() {
        // /topic is broker-managed and broadcast: nothing here publishes to one, and a subscriber to a
        // topic would receive whatever a later feature did.
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/topic/notifications", session());

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASubscribeFromASessionThatNeverConnected_WhenItArrives_ThenItIsRefused() {
        Message<?> subscribe = frameFrom(StompCommand.SUBSCRIBE, "/user/queue/notifications", null);

        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenASendFrame_WhenItArrives_ThenItIsRefusedBecauseNothingServerSideReceivesOne() {
        // There is no @MessageMapping in the application, so a SEND can only be probing for one.
        Message<?> send = frameFrom(StompCommand.SEND, "/app/anything", session());

        assertThatThrownBy(() -> interceptor.preSend(send, channel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenAHeartbeat_WhenItArrives_ThenItPasses() {
        // A heartbeat carries no command at all. Reading one as an unauthorised frame would close every
        // idle socket on its first keepalive.
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        accessor.setLeaveMutable(true);
        Message<byte[]> heartbeat = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(heartbeat, channel)).isSameAs(heartbeat);
    }

    @Test
    void GivenAnUnsubscribe_WhenItArrives_ThenItPasses() {
        Message<?> unsubscribe = frameFrom(StompCommand.UNSUBSCRIBE, null, session());

        assertThat(interceptor.preSend(unsubscribe, channel)).isSameAs(unsubscribe);
    }

    @Test
    void GivenADisconnect_WhenItArrives_ThenItPasses() {
        // Refusing this would leave the session to be torn down by a timeout instead of a clean close.
        Message<?> disconnect = frameFrom(StompCommand.DISCONNECT, null, session());

        assertThat(interceptor.preSend(disconnect, channel)).isSameAs(disconnect);
    }

    /** The principal a CONNECT leaves on the session, which later frames arrive carrying. */
    private static Principal session() {
        return new StompPrincipal(UUID.randomUUID().toString());
    }

    private static Message<byte[]> frameFrom(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
