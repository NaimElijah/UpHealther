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

    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
